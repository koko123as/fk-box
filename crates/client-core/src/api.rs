//! The client layer's call surface for a platform host: `method` with JSON
//! `args`, answered with a JSON value. On Android the Kotlin service layer
//! (`database/CoreStore.kt`) calls it over JNI, so the service keeps reading
//! the selected profile and its settings at start without the UI running.
//!
//! Calls that need the cores (a subscription fetch, a content save) take the
//! platform's [`Kernel`] ([`call_with_kernel`]); the rest need only the store.

use serde_json::{json, Map, Value};

use crate::content::{self, Kernel, Replace};
use crate::profile_array::{self, Element};
use crate::storage::{Profile, RemoteServer, SettingValue, Store};
use crate::subscription::{self, Fetch, Metadata};

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
    let e = |error: crate::storage::Error| error.0;
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
        "meta.migration" => store.meta(crate::legacy::MIGRATION_KEY).map_err(e)?.map(Value::String).unwrap_or(Value::Null),
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
        let (store, _dir, id) = crate::content::tests::setup(true, Some("old"));
        assert_eq!(call(&store, "content.replace", &json!({"id": id}).to_string()).unwrap_err(), "content.replace needs the kernel bridge");
        let mut kernel = crate::content::tests::FakeKernel {
            response: crate::subscription::Response { content: "new".into(), user_info: "total=3".into(), ..Default::default() },
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
