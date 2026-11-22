//! The store's call surface for a platform host: `method` with JSON `args`,
//! answered with a JSON value. On Android the Kotlin service layer
//! (`database/CoreStore.kt`) calls it over one JNI entry point, so the
//! service keeps reading the selected profile and its settings at start
//! without the UI running.

use serde_json::{json, Map, Value};

use crate::storage::{Profile, RemoteServer, SettingValue, Store};

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

/// Runs one call. Errors are messages for the caller (thrown as exceptions
/// on the Kotlin side).
pub fn call(store: &Store, method: &str, args: &str) -> Result<String, String> {
    let args: Value =
        if args.trim().is_empty() { Value::Null } else { serde_json::from_str(args).map_err(|e| format!("Invalid arguments: {e}"))? };
    let e = |error: crate::storage::Error| error.0;
    let value = match method {
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
