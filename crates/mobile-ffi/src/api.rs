//! The client layer's call surface for a platform host: `method` with JSON
//! `args`, answered with a JSON value. On Android the Kotlin service layer
//! (`database/CoreStore.kt`) calls it over JNI (through `apps/android/native`), so the service keeps reading
//! the selected profile and its settings at start without the UI running.
//!
//! Calls that need the cores (a subscription fetch, a content save) take the
//! platform's [`Kernel`] ([`call_with_kernel`]); the rest need only the store.

use serde_json::{json, Map, Value};

use fkbox_client_core::content::{self, Kernel, Replace};
use fkbox_client_core::service_settings::{self, Written};
use fkbox_client_core::{backup, capture, geo, ip_quality, profiles, remote_servers, routing, terminal, updates, webdav};

use crate::sessions;
use fkbox_client_core::profile_array::{self, Element};
use fkbox_client_core::storage::{Profile, RemoteServer, SettingValue, Store};
use fkbox_client_core::subscription::{self, Fetch, Metadata};

fn arg<'a>(args: &'a Value, key: &str) -> Result<&'a Value, String> {
    args.get(key).filter(|v| !v.is_null()).ok_or_else(|| format!("Missing argument: {key}"))
}

fn id(args: &Value, key: &str) -> Result<i64, String> {
    arg(args, key)?.as_i64().ok_or_else(|| format!("Invalid argument: {key}"))
}

fn text<'a>(args: &'a Value, key: &str) -> Result<&'a str, String> {
    arg(args, key)?.as_str().ok_or_else(|| format!("Invalid argument: {key}"))
}

fn object<'a>(args: &'a Value, key: &str) -> Result<&'a Map<String, Value>, String> {
    arg(args, key)?.as_object().ok_or_else(|| format!("Invalid argument: {key}"))
}

fn ids(args: &Value, key: &str) -> Result<Vec<i64>, String> {
    arg(args, key)?
        .as_array()
        .ok_or_else(|| format!("Invalid argument: {key}"))?
        .iter()
        .map(|v| v.as_i64().ok_or_else(|| format!("Invalid argument: {key}")))
        .collect()
}

fn parsed<T: for<'de> serde::Deserialize<'de>>(args: &Value, key: &str) -> Result<T, String> {
    serde_json::from_value(arg(args, key)?.clone()).map_err(|e| format!("Invalid argument: {key}: {e}"))
}

fn strings(args: &Value, key: &str) -> Result<Vec<String>, String> {
    arg(args, key)?
        .as_array()
        .ok_or_else(|| format!("Invalid argument: {key}"))?
        .iter()
        .map(|v| v.as_str().map(str::to_string).ok_or_else(|| format!("Invalid argument: {key}")))
        .collect()
}

fn text_or<'a>(args: &'a Value, key: &str, default: &'a str) -> &'a str {
    args.get(key).and_then(Value::as_str).unwrap_or(default)
}

fn form_context(args: &Value) -> Result<profiles::Context, String> {
    Ok(profiles::Context { configs_dir: text(args, "configsDir")?.into(), now: id(args, "now")? })
}

fn to_json<T: serde::Serialize>(value: &T) -> Result<Value, String> {
    serde_json::to_value(value).map_err(|e| e.to_string())
}

/// Runs one call. Errors are messages for the caller (thrown as exceptions
/// on the Kotlin side).
pub fn call(store: &Store, method: &str, args: &str) -> Result<String, String> {
    dispatch(store, None, method, args)
}

/// [`call`] for a caller that can reach the cores through `kernel`.
pub fn call_with_kernel(store: &Store, kernel: &mut dyn Kernel, method: &str, args: &str) -> Result<String, String> {
    dispatch(store, Some(kernel), method, args)
}

fn dispatch(store: &Store, kernel: Option<&mut dyn Kernel>, method: &str, args: &str) -> Result<String, String> {
    let args: Value =
        if args.trim().is_empty() { Value::Null } else { serde_json::from_str(args).map_err(|e| format!("Invalid arguments: {e}"))? };
    let e = |error: fkbox_client_core::storage::Error| error.0;
    let has_kernel = kernel.is_some();
    let kernel = move || kernel.ok_or_else(|| format!("{method} needs the kernel bridge"));
    let value = match method {
        // -- subscriptions -------------------------------------------------
        "subscription.fetch" => to_json(&content::fetch(kernel()?, text(&args, "url")?, text(&args, "core")?)?)?,
        "subscription.metadataAfter" => {
            let interval = arg(&args, "interval")?.as_i64().and_then(|v| i32::try_from(v).ok()).ok_or("Invalid argument: interval")?;
            let user_set = arg(&args, "intervalUserSet")?.as_bool().ok_or("Invalid argument: intervalUserSet")?;
            let fetch: Fetch = parsed(&args, "fetch")?;
            to_json(&Metadata::after(interval, user_set, &fetch))?
        }
        "subscription.webPageUrl" => subscription::parse_web_page_url(args.get("value").and_then(Value::as_str).unwrap_or("")).into(),
        "subscription.schedule" => to_json(&subscription::schedule(&store.profiles().map_err(e)?, id(&args, "now")?))?,
        "subscription.due" => to_json(&subscription::due(&store.profiles().map_err(e)?, id(&args, "now")?))?,
        "subscription.isDue" => {
            store.profile(id(&args, "id")?).map_err(e)?.is_some_and(|p| subscription::is_due(&p, id(&args, "now").unwrap_or(0))).into()
        }
        "subscription.updateAll" => to_json(&subscription::update_all(&store.profiles().map_err(e)?))?,
        "profileArray.entries" => {
            let elements: Vec<Element> = parsed(&args, "elements")?;
            to_json(&profile_array::entries(text(&args, "source")?, &elements))?
        }
        // -- profile content -----------------------------------------------
        "content.replace" => {
            let request: Replace = serde_json::from_value(args.clone()).map_err(|e| format!("Invalid arguments: {e}"))?;
            to_json(&content::replace(store, kernel()?, request)?)?
        }
        "content.finish" => {
            content::finish(store, id(&args, "token")? as u64);
            Value::Null
        }
        "content.rollback" => {
            content::rollback(store, id(&args, "token")? as u64)?;
            Value::Null
        }
        "content.revision" => content::revision(text(&args, "content")?).into(),
        // -- profile form, import and delete (phase 2e) ----------------------
        "profiles.save" => {
            let form: profiles::Form = parsed(&args, "profile")?;
            to_json(&profiles::save(store, kernel()?, form, &form_context(&args)?)?)?
        }
        "profiles.saveAll" => {
            let entries: Vec<profiles::ImportEntry> = parsed(&args, "entries")?;
            to_json(&profiles::save_all(store, kernel()?, entries, text_or(&args, "core", "auto"), &form_context(&args)?)?)?
        }
        "profiles.remove" => to_json(&profiles::delete(store, id(&args, "id")?)?)?,
        "profiles.finish" => {
            profiles::finish(store, id(&args, "token")? as u64);
            Value::Null
        }
        "profiles.rollback" => json!({ "written": to_json(&profiles::rollback(store, id(&args, "token")? as u64)?)? }),
        // -- service settings ----------------------------------------------
        "serviceSettings.snapshot" => Value::Object(service_settings::snapshot(store)?),
        "serviceSettings.backup" => Value::Object(service_settings::backup(store)?),
        "serviceSettings.validate" => {
            service_settings::validate(object(&args, "values")?)?;
            Value::Null
        }
        "serviceSettings.plan" => to_json(&service_settings::plan(store, object(&args, "values")?)?)?,
        "serviceSettings.patch" => {
            let values = object(&args, "values")?;
            let plan = service_settings::plan(store, values)?;
            let written = service_settings::patch(store, values)?;
            json!({ "written": to_json(&written)?, "plan": to_json(&plan)? })
        }
        // -- routing rules -------------------------------------------------
        "routing.normalize" => routing::to_wire(&routing::from_wire(arg(&args, "value")?)?),
        "routing.saved" => routing::to_wire(&routing::load(store)),
        "routing.coreJson" => match args.get("value").filter(|v| !v.is_null()) {
            Some(draft) => routing::core_json(&routing::from_wire(draft)?, text(&args, "core")?).into(),
            None => routing::saved_core_json(store, text(&args, "core")?)?.into(),
        },
        // -- geo resources -------------------------------------------------
        "geo.schedule" => to_json(&geo::schedule(store)?)?,
        "geo.needed" => to_json(&geo::needed(text(&args, "core")?, text(&args, "text")?))?,
        "geo.shouldExtract" => {
            let installed: Option<geo::Installed> = match args.get("installed").filter(|v| !v.is_null()) {
                Some(v) => Some(serde_json::from_value(v.clone()).map_err(|e| format!("Invalid argument: installed: {e}"))?),
                None => None,
            };
            geo::should_extract(
                arg(&args, "exists")?.as_bool().ok_or("Invalid argument: exists")?,
                installed.as_ref(),
                text(&args, "bundledSha256")?,
                id(&args, "bundleTime")?,
                id(&args, "fileModified")?,
            )
            .into()
        }
        "geo.due" => {
            let interval = geo::schedule(store)?.interval_hours;
            to_json(&geo::due(args.get("state").and_then(Value::as_str).unwrap_or(""), &strings(&args, "installed")?, interval, id(&args, "now")?))?
        }
        "geo.custom" => to_json(&geo::custom_urls(store)?)?,
        "geo.url" => geo::url(store, text(&args, "name")?, text(&args, "defaultUrl")?)?.into(),
        "geo.validUrl" => geo::valid_url(text(&args, "url")?).into(),
        "geo.setUrl" => {
            let stored = geo::set_url(store, text(&args, "name")?, text(&args, "url")?, text(&args, "defaultUrl")?)?;
            let written: Vec<Written> = stored.iter().map(|v| Written::of(geo::URLS_KEY, v)).collect();
            json!({ "written": to_json(&written)? })
        }
        // -- backups ---------------------------------------------------------
        "backup.create" => backup::encode(&backup::create(store, kernel()?, text(&args, "generated")?)?)?.into(),
        "backup.read" => backup::read(text(&args, "text")?, text(&args, "scope")?, kernel()?)?,
        "backup.restore" => {
            let dirs = backup::Dirs { files: text(&args, "filesDir")?.into(), cache: text(&args, "cacheDir")?.into() };
            let done = backup::restore_staged(store, kernel()?, text(&args, "token")?, text(&args, "scope")?, text(&args, "strategy")?, &dirs)?;
            to_json(&done)?
        }
        "backup.finish" => {
            backup::finish(id(&args, "token")? as u64);
            Value::Null
        }
        "backup.rollback" => json!({ "written": to_json(&backup::rollback(store, id(&args, "token")? as u64)?)? }),
        // -- WebDAV ----------------------------------------------------------
        "webdav.account" => webdav::account(store, kernel()?)?.map(Value::Object).unwrap_or(Value::Null),
        "webdav.save" => {
            let value = args.get("value").and_then(Value::as_object);
            if store.in_transaction() {
                return Err("Wait for the restore to finish".into());
            }
            let kernel = kernel()?;
            webdav::save(store, kernel, value)?;
            webdav::account(store, kernel)?.map(Value::Object).unwrap_or(Value::Null)
        }
        "webdav.check" => {
            webdav::check(store, kernel()?)?;
            true.into()
        }
        "webdav.upload" => {
            let kernel = kernel()?;
            let text = backup::encode(&backup::create(store, kernel, text(&args, "generated")?)?)?;
            webdav::upload(store, kernel, &text)?;
            true.into()
        }
        "webdav.read" => {
            let kernel = kernel()?;
            let text = webdav::download(store, kernel)?;
            backup::read(&text, text_or(&args, "scope", "all"), kernel)?
        }
        // -- update check ----------------------------------------------------
        "updates.check" => updates::check(kernel()?, &parsed(&args, "build")?)?,
        // -- the service's start decisions (phase 4e3b) ----------------------
        "service.routingStatus" => crate::service::routing_status(&args),
        "service.geoMissing" => crate::service::geo_missing(&args),
        "service.perAppPackages" => crate::service::per_app_packages(&args),
        // -- the service's start and stop state machine (phase 4i1) ---------
        "lifecycle.startCommand" => crate::lifecycle::start_command(&args),
        "lifecycle.stopRequest" => crate::lifecycle::stop_request(&args),
        "lifecycle.stopFinished" => crate::lifecycle::stop_finished(&args),
        "lifecycle.startSucceeded" => crate::lifecycle::start_succeeded(&args),
        "lifecycle.profileCheck" => crate::lifecycle::profile_check(&args),
        "lifecycle.startFailure" => crate::lifecycle::start_failure(&args),
        "lifecycle.alertText" => crate::lifecycle::alert_text(&args),
        "lifecycle.reloadFallback" => crate::lifecycle::reload_fallback(&args),
        "lifecycle.wifiPermission" => crate::lifecycle::wifi_permission(&args),
        "lifecycle.notification" => crate::lifecycle::notification(&args),
        "lifecycle.trafficShown" => crate::lifecycle::traffic_shown(&args),
        "lifecycle.trafficText" => crate::lifecycle::traffic_text(&args),
        // -- command subscriptions (phase 3b) ------------------------------
        "sessions.plan" => {
            let inputs: sessions::Inputs = if args.is_null() {
                sessions::Inputs::default()
            } else {
                serde_json::from_value(args.clone()).map_err(|e| format!("Invalid arguments: {e}"))?
            };
            to_json(&sessions::plan(&inputs))?
        }
        // -- terminal settings -----------------------------------------------
        // -- capture settings and the IP quality cache (phase 2e) ------------
        "capture.snapshot" => capture::snapshot(std::path::Path::new(text(&args, "dir")?), kernel()?)?,
        "capture.config" => capture::config(std::path::Path::new(text(&args, "dir")?), kernel()?)?.into(),
        "capture.save" => {
            capture::save(std::path::Path::new(text(&args, "dir")?), kernel()?, text(&args, "revision")?, text(&args, "content")?)?
        }
        "ipQuality.read" => ip_quality::read(store, text(&args, "ip")?)?.unwrap_or(Value::Null),
        "ipQuality.write" => {
            ip_quality::write(store, text(&args, "ip")?, arg(&args, "value")?, id(&args, "now")?)?;
            Value::Null
        }
        "terminal.snapshot" => Value::Object(terminal::snapshot(store)?),
        "terminal.set" => {
            let key = text(&args, "key")?;
            let value = args.get("value").cloned().unwrap_or(Value::Null);
            let written = terminal::set(store, key, &value, if has_kernel { Some(kernel()?) } else { None })?;
            json!({ "written": to_json(&written)?, "snapshot": Value::Object(terminal::snapshot(store)?) })
        }
        "terminal.useFont" => json!({ "written": to_json(&terminal::use_imported_font(store, text(&args, "path")?)?)? }),
        "terminal.fontDeleted" => json!({ "written": to_json(&terminal::font_deleted(store, text(&args, "path")?)?)? }),
        // -- store ---------------------------------------------------------
        "settings.all" => {
            let mut out = Map::new();
            for (key, value) in store.settings().map_err(e)? {
                out.insert(key, json!({ "t": value.kind(), "v": value.to_json() }));
            }
            Value::Object(out)
        }
        "settings.put" => {
            let kind = arg(&args, "t")?.as_i64().ok_or("Invalid argument: t")?;
            let value = SettingValue::from_json(kind, arg(&args, "v")?).map_err(e)?;
            store.put_setting(text(&args, "key")?, &value).map_err(e)?;
            Value::Null
        }
        "settings.remove" => {
            store.remove_setting(text(&args, "key")?).map_err(e)?;
            Value::Null
        }
        "profiles.list" => serde_json::to_value(store.profiles().map_err(e)?).map_err(|e| e.to_string())?,
        "profiles.get" => serde_json::to_value(store.profile(id(&args, "id")?).map_err(e)?).map_err(|e| e.to_string())?,
        "profiles.insert" => {
            let profile: Profile = serde_json::from_value(arg(&args, "profile")?.clone()).map_err(|e| format!("Invalid profile: {e}"))?;
            store.insert_profile(&profile).map_err(e)?.into()
        }
        "profiles.update" => {
            let profile: Profile = serde_json::from_value(arg(&args, "profile")?.clone()).map_err(|e| format!("Invalid profile: {e}"))?;
            let write_order = args.get("writeOrder").and_then(Value::as_bool).unwrap_or(false);
            store.update_profile(&profile, write_order).map_err(e)?.into()
        }
        "profiles.delete" => store.delete_profiles(&ids(&args, "ids")?).map_err(e)?.into(),
        "profiles.nextOrder" => store.next_profile_order().map_err(e)?.into(),
        "profiles.reorder" => {
            store.reorder_profiles(&ids(&args, "ids")?).map_err(e)?;
            Value::Null
        }
        "profiles.setResolvedCore" => {
            store.set_resolved_core(id(&args, "id")?, text(&args, "core")?, text(&args, "stamp")?).map_err(e)?;
            Value::Null
        }
        "servers.list" => serde_json::to_value(store.remote_servers().map_err(e)?).map_err(|e| e.to_string())?,
        "servers.get" => serde_json::to_value(store.remote_server(id(&args, "id")?).map_err(e)?).map_err(|e| e.to_string())?,
        "servers.insert" => {
            let server: RemoteServer = serde_json::from_value(arg(&args, "server")?.clone()).map_err(|e| format!("Invalid server: {e}"))?;
            store.insert_remote_server(&server).map_err(e)?.into()
        }
        "servers.update" => {
            let server: RemoteServer = serde_json::from_value(arg(&args, "server")?.clone()).map_err(|e| format!("Invalid server: {e}"))?;
            store.update_remote_server(&server).map_err(e)?.into()
        }
        "servers.save" => remote_servers::save(store, id(&args, "id").unwrap_or(0), text_or(&args, "name", ""), text(&args, "url")?, text_or(&args, "secret", ""))?.into(),
        "servers.connectUrl" => remote_servers::connect_url(text(&args, "url")?).into(),
        "servers.displayName" => remote_servers::display_name(text_or(&args, "name", ""), text(&args, "url")?).into(),
        "servers.delete" => store.delete_remote_server(id(&args, "id")?).map_err(e)?.into(),
        "servers.nextOrder" => store.next_remote_server_order().map_err(e)?.into(),
        "preferences.get" => Value::Object(store.ui_preferences().map_err(e)?),
        "preferences.raw" => Value::Object(store.ui_preferences_raw().map_err(e)?),
        "preferences.replace" => Value::Object(store.replace_ui_preferences(object(&args, "values")?).map_err(e)?),
        "preferences.update" => Value::Object(store.update_ui_preferences(object(&args, "values")?).map_err(e)?),
        "backup.origin" => {
            let generated = text(&args, "generated")?.to_string();
            store.backup_origin(|| generated).map_err(e)?.into()
        }
        "tx.begin" => {
            store.begin().map_err(e)?;
            Value::Null
        }
        "tx.commit" => {
            store.commit().map_err(e)?;
            Value::Null
        }
        "tx.rollback" => {
            store.rollback().map_err(e)?;
            Value::Null
        }
        "meta.migration" => store.meta(fkbox_client_core::legacy::MIGRATION_KEY).map_err(e)?.map(Value::String).unwrap_or(Value::Null),
        _ => return Err(format!("Unknown store call: {method}")),
    };
    Ok(value.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(store: &Store, method: &str, args: Value) -> Value {
        serde_json::from_str(&call(store, method, &args.to_string()).unwrap_or_else(|e| panic!("{method}: {e}"))).unwrap()
    }

    #[test]
    fn settings_round_trip() {
        let store = Store::open_in_memory().unwrap();
        run(&store, "settings.put", json!({"key": "selected_profile", "t": 3, "v": 4}));
        run(&store, "settings.put", json!({"key": "per_app_proxy_list", "t": 5, "v": ["a"]}));
        assert_eq!(
            run(&store, "settings.all", Value::Null),
            json!({"per_app_proxy_list": {"t": 5, "v": ["a"]}, "selected_profile": {"t": 3, "v": 4}})
        );
        run(&store, "settings.remove", json!({"key": "selected_profile"}));
        assert_eq!(run(&store, "settings.all", Value::Null).as_object().unwrap().len(), 1);
        assert!(call(&store, "settings.put", &json!({"key": "x", "t": 1, "v": "no"}).to_string()).is_err());
    }

    #[test]
    fn profiles_round_trip() {
        let store = Store::open_in_memory().unwrap();
        let id = run(&store, "profiles.insert", json!({"profile": {"name": "a", "path": "/a.json", "core": "xray", "userOrder": 0}}));
        let id = id.as_i64().unwrap();
        assert_eq!(run(&store, "profiles.nextOrder", Value::Null), json!(1));
        let mut profile = run(&store, "profiles.get", json!({"id": id}));
        assert_eq!(profile["core"], json!("xray"));
        profile["name"] = json!("b");
        assert_eq!(run(&store, "profiles.update", json!({"profile": profile})), json!(1));
        run(&store, "profiles.setResolvedCore", json!({"id": id, "core": "xray", "stamp": "s"}));
        let list = run(&store, "profiles.list", Value::Null);
        assert_eq!((list[0]["name"].clone(), list[0]["resolvedCore"].clone()), (json!("b"), json!("xray")));
        run(&store, "profiles.reorder", json!({"ids": [id]}));
        assert_eq!(run(&store, "profiles.delete", json!({"ids": [id]})), json!(1));
        assert_eq!(run(&store, "profiles.get", json!({"id": id})), Value::Null);
    }

    #[test]
    fn servers_preferences_backup_and_transactions() {
        let store = Store::open_in_memory().unwrap();
        let id = run(&store, "servers.insert", json!({"server": {"name": "n", "url": "u", "secret": ""}})).as_i64().unwrap();
        assert_eq!(run(&store, "servers.list", Value::Null)[0]["id"], json!(id));
        assert_eq!(run(&store, "preferences.update", json!({"values": {"theme": "dark"}})), json!({"theme": "dark"}));
        assert_eq!(run(&store, "preferences.replace", json!({"values": {"pureBlack": true}})), json!({"pureBlack": true}));
        assert_eq!(run(&store, "preferences.get", Value::Null), json!({"pureBlack": true}));
        assert_eq!(run(&store, "backup.origin", json!({"generated": "g1"})), json!("g1"));
        assert_eq!(run(&store, "backup.origin", json!({"generated": "g2"})), json!("g1"));
        run(&store, "tx.begin", Value::Null);
        run(&store, "servers.delete", json!({"id": id}));
        run(&store, "tx.rollback", Value::Null);
        assert_eq!(run(&store, "servers.list", Value::Null).as_array().unwrap().len(), 1);
        assert_eq!(run(&store, "meta.migration", Value::Null), Value::Null);
    }

    #[test]
    fn subscription_policy_calls() {
        let store = Store::open_in_memory().unwrap();
        let now = 10_000_000_000_i64;
        let a = run(&store, "profiles.insert", json!({"profile": {"type": 1, "autoUpdate": true, "autoUpdateInterval": 30, "lastUpdated": now - 3_600_000}}));
        run(&store, "profiles.insert", json!({"profile": {"type": 1, "autoUpdateInterval": 30, "userOrder": 1}}));
        assert_eq!(run(&store, "subscription.schedule", json!({"now": now})), json!({"intervalMinutes": 30, "initialDelaySeconds": 0}));
        assert_eq!(run(&store, "subscription.due", json!({"now": now})), json!([a]));
        assert_eq!(run(&store, "subscription.isDue", json!({"id": a, "now": now})), json!(true));
        assert_eq!(run(&store, "subscription.isDue", json!({"id": 99, "now": now})), json!(false));
        assert_eq!(run(&store, "subscription.updateAll", Value::Null).as_array().unwrap().len(), 2);
        let metadata = run(
            &store,
            "subscription.metadataAfter",
            json!({"interval": 60, "intervalUserSet": false, "fetch": {"info": {"upload": 1}, "updateIntervalMinutes": 5, "webPageUrl": "https://x.example"}}),
        );
        assert_eq!(
            metadata,
            json!({"upload": 1, "download": 0, "total": 0, "expire": 0, "webPageUrl": "https://x.example", "autoUpdateInterval": 15})
        );
        assert_eq!(run(&store, "subscription.webPageUrl", json!({"value": "javascript:1"})), json!(""));
        let entries = run(&store, "profileArray.entries", json!({"source": "s", "elements": [{"remarks": "", "content": "{}", "core": "xray"}, {"remarks": "b", "content": "[]", "core": "xray"}]}));
        assert_eq!(entries, json!([{"name": "s #1", "content": "{}", "core": "xray"}, {"name": "b", "content": "[]", "core": "xray"}]));
        assert_eq!(run(&store, "content.revision", json!({"content": ""})).as_str().unwrap().len(), 64);
        run(&store, "content.finish", json!({"token": 5}));
        run(&store, "content.rollback", json!({"token": 5}));
    }

    #[test]
    fn kernel_calls_need_the_bridge_and_use_it() {
        let (store, _dir, id) = fkbox_client_core::content::tests::setup(true, Some("old"));
        assert_eq!(call(&store, "content.replace", &json!({"id": id}).to_string()).unwrap_err(), "content.replace needs the kernel bridge");
        let mut kernel = fkbox_client_core::content::tests::FakeKernel {
            response: fkbox_client_core::subscription::Response { content: "new".into(), user_info: "total=3".into(), ..Default::default() },
            ..Default::default()
        };
        let fetched: Value = serde_json::from_str(
            &call_with_kernel(&store, &mut kernel, "subscription.fetch", &json!({"url": "https://e.example/s", "core": "auto"}).to_string()).unwrap(),
        )
        .unwrap();
        assert_eq!(fetched["info"]["total"], json!(3));
        assert!(call_with_kernel(&store, &mut kernel, "subscription.fetch", &json!({"url": "file:///x", "core": "auto"}).to_string()).is_err());
        let args = json!({"id": id, "subscription": true, "touch": true, "selected": true, "now": 9});
        let done: Value = serde_json::from_str(&call_with_kernel(&store, &mut kernel, "content.replace", &args.to_string()).unwrap()).unwrap();
        assert_eq!((done["changed"].clone(), done["apply"].clone()), (json!(true), json!(true)));
        let token = done["token"].as_i64().unwrap();
        run(&store, "content.rollback", json!({"token": token}));
        assert_eq!(store.profile(id).unwrap().unwrap().last_updated, 1_000);
    }

    #[test]
    fn service_settings_routing_and_geo_calls() {
        let store = Store::open_in_memory().unwrap();
        assert_eq!(run(&store, "serviceSettings.snapshot", Value::Null)["oomMemoryLimitMB"], json!(50));
        assert!(run(&store, "serviceSettings.backup", Value::Null).get("perAppManagedList").is_none());
        assert!(call(&store, "serviceSettings.validate", &json!({"values": {"oomMemoryLimitMB": 1}}).to_string()).is_err());
        let plan = run(&store, "serviceSettings.plan", json!({"values": {"queryMode": "ROOT"}}));
        assert_eq!(plan, json!({"queryMode": "ROOT", "scan": false, "reloadSetup": false, "rescheduleGeo": false}));
        let rules = json!({"cores": {"mihomo": {"rules": [{"id": "a", "content": "all", "apps": ["com.tencent.mm"], "target": {"type": "direct"}}]}}});
        let done = run(&store, "serviceSettings.patch", json!({"values": {"routingRules": rules, "geoAutoUpdate": true}}));
        assert_eq!(done["plan"]["rescheduleGeo"], json!(true));
        assert_eq!(done["written"].as_array().unwrap().len(), 2);
        assert_eq!(run(&store, "routing.saved", Value::Null)["cores"]["mihomo"]["mode"], json!("prepend"));
        let core = run(&store, "routing.coreJson", json!({"core": "mihomo"}));
        assert!(core.as_str().unwrap().contains("com.tencent.mm"));
        assert_eq!(run(&store, "routing.coreJson", json!({"core": "xray"})), json!(""));
        let draft = run(&store, "routing.coreJson", json!({"core": "xray", "value": {"cores": {"xray": {}}}}));
        assert!(draft.as_str().unwrap().starts_with(r#"{"mode":"prepend""#));
        assert_eq!(run(&store, "routing.normalize", json!({"value": {"cores": {}}})), json!({"version": 1, "cores": {}}));
        assert!(call(&store, "routing.normalize", &json!({"value": {"version": 3}}).to_string()).is_err());

        assert_eq!(run(&store, "geo.schedule", Value::Null), json!({"enabled": true, "intervalHours": 24}));
        assert_eq!(run(&store, "geo.needed", json!({"core": "mihomo", "text": "rules: []"})), json!(["geosite", "mmdb"]));
        let extract = json!({"exists": true, "installed": {"source": "builtin", "sha256": "x", "time": 1}, "bundledSha256": "y", "bundleTime": 2, "fileModified": 0});
        assert_eq!(run(&store, "geo.shouldExtract", extract), json!(true));
        assert_eq!(run(&store, "geo.shouldExtract", json!({"exists": true, "bundledSha256": "y", "bundleTime": 2, "fileModified": 0})), json!(false));
        assert_eq!(run(&store, "geo.due", json!({"state": "", "installed": ["asn", "geoip"], "now": 5})), json!(["geoip", "asn"]));
        let set = run(&store, "geo.setUrl", json!({"name": "asn", "url": "https://m.example/asn", "defaultUrl": "https://d.example/asn"}));
        assert_eq!(set["written"][0]["key"], json!("geo_urls"));
        assert_eq!(run(&store, "geo.url", json!({"name": "asn", "defaultUrl": "https://d.example/asn"})), json!("https://m.example/asn"));
        assert_eq!(run(&store, "geo.custom", Value::Null), json!({"asn": "https://m.example/asn"}));
        assert_eq!(run(&store, "geo.validUrl", json!({"url": "https://"})), json!(false));
    }

    #[test]
    fn terminal_webdav_and_backup_calls() {
        let store = Store::open_in_memory().unwrap();
        assert_eq!(run(&store, "terminal.snapshot", Value::Null)["fontSize"], json!(14));
        let set = run(&store, "terminal.set", json!({"key": "fontSize", "value": 20}));
        assert_eq!((set["written"][0]["key"].clone(), set["snapshot"]["fontSize"].clone()), (json!("tailscale_ssh_font_size"), json!(20)));
        assert_eq!(call(&store, "terminal.set", &json!({"key": "darkTheme", "value": "x"}).to_string()).unwrap_err(), "Setting darkTheme needs the platform");
        assert_eq!(run(&store, "terminal.useFont", json!({"path": "/f.ttf"}))["written"].as_array().unwrap().len(), 3);
        assert_eq!(run(&store, "terminal.fontDeleted", json!({"path": "/f.ttf"}))["written"][0]["v"], json!(""));
        for method in ["webdav.account", "webdav.check", "updates.check", "backup.create"] {
            assert_eq!(call(&store, method, "{}").unwrap_err(), format!("{method} needs the kernel bridge"));
        }
        let mut dav = fkbox_client_core::webdav::tests::Dav::default();
        let saved: Value = serde_json::from_str(
            &call_with_kernel(&store, &mut dav, "webdav.save", &json!({"value": fkbox_client_core::webdav::tests::account_value()}).to_string()).unwrap(),
        )
        .unwrap();
        assert_eq!(saved["password"], json!("pw"));
        assert_eq!(call_with_kernel(&store, &mut dav, "webdav.save", "{}").unwrap(), "null");
        assert!(call(&store, "backup.rollback", &json!({"token": 12345}).to_string()).is_err());
        run(&store, "backup.finish", json!({"token": 12345}));
    }

    #[test]
    fn bad_calls_fail_with_messages() {
        let store = Store::open_in_memory().unwrap();
        assert_eq!(call(&store, "nope", "{}").unwrap_err(), "Unknown store call: nope");
        assert_eq!(call(&store, "profiles.get", "{}").unwrap_err(), "Missing argument: id");
        assert!(call(&store, "profiles.get", "{").is_err());
        assert!(call(&store, "preferences.update", &json!({"values": {"theme": 1}}).to_string())
            .unwrap_err()
            .contains("Invalid interface preference"));
    }
}
