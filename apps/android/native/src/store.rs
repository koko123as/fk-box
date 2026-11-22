//! The process's client store ([`fkbox_client_core::storage::Store`]) and its
//! JNI surface for the Kotlin service layer (`database/CoreStore.kt`).
//!
//! Kotlin opens the store on first use (`nativeOpen`, with the app's paths),
//! which also runs the one-time import of the Room databases; the UI takes
//! the same instance in `android_main` ([`store`]), so the process has one
//! connection to the file. libfkui.so is loaded by `CoreStore` before the
//! activity exists, so the service can start (boot, tile) without the UI.
//!
//! Calls that need the cores (`nativeCallKernel`: subscription fetches and
//! profile content saves, `fkbox_client_core::content`) get the Kotlin
//! kernel bridge (`database/CoreKernel.kt`) with the call; Rust calls back
//! into it on the same thread, and it runs libbox in Go.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::{jni_sig, jni_str, EnvUnowned};
use serde_json::Value;

use fkbox_client_core::content::Kernel;
use fkbox_client_core::legacy::{self, Outcome, Sources};
use fkbox_client_core::{api, storage::Store};

static STORE: Mutex<Option<Arc<Store>>> = Mutex::new(None);

/// The store Kotlin opened, if it has.
pub fn store() -> Option<Arc<Store>> {
    STORE.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

/// Opens the store at `path` (once per process; later calls return the
/// summary of an import already recorded) and imports the earlier storage:
/// the Room databases and interface preferences (`sources`), and the WebDAV
/// account from SharedPreferences `bbox_webdav` (`webdav`, its own one-time
/// import, phase 2d) and the dashboard's IP quality cache from
/// `dashboard_ip_quality` beside it (phase 2e).
pub fn open(path: PathBuf, sources: Sources, webdav: PathBuf) -> Result<String, String> {
    let mut slot = STORE.lock().unwrap_or_else(|e| e.into_inner());
    let store = match slot.as_ref() {
        Some(store) => store.clone(),
        None => {
            let store = Arc::new(Store::open(&path).map_err(|e| e.0)?);
            *slot = Some(store.clone());
            store
        }
    };
    drop(slot);
    // An import failure is reported, not fatal: the store stays empty and
    // unmarked, and the next start tries again.
    let summary = match legacy::migrate(&store, &sources) {
        Ok(Outcome::AlreadyDone(summary)) => summary,
        Ok(Outcome::Done(summary)) => serde_json::to_string(&summary).unwrap_or_default(),
        Err(error) => serde_json::json!({ "status": "failed", "error": error.0 }).to_string(),
    };
    let webdav_path = webdav;
    let webdav = match legacy::migrate_webdav(&store, &webdav_path, &sources.scratch_dir) {
        Ok(summary) => summary,
        Err(error) => serde_json::json!({ "status": "failed", "error": error.0 }).to_string(),
    };
    // The dashboard's IP quality cache sits next to `bbox_webdav` in
    // shared_prefs (phase 2e, its own one-time import).
    let quality_xml = webdav_path.with_file_name("dashboard_ip_quality.xml");
    let quality = match fkbox_client_core::ip_quality::migrate(&store, &quality_xml, &sources.scratch_dir) {
        Ok(summary) => summary,
        Err(error) => serde_json::json!({ "status": "failed", "error": error.0 }).to_string(),
    };
    Ok(format!("{summary}; webdav: {webdav}; ipQuality: {quality}"))
}

fn call(method: &str, args: &str) -> Result<String, String> {
    let store = store().ok_or("The client store is not open")?;
    api::call(&store, method, args)
}

/// The Kotlin kernel bridge passed with a call: `CoreKernel.call(method,
/// argsJson)` answers `{"ok": value}` or `{"error": message}`.
struct JniKernel<'a, 'local> {
    env: &'a mut jni::Env<'local>,
    bridge: &'a JObject<'local>,
}

impl Kernel for JniKernel<'_, '_> {
    fn call(&mut self, method: &str, args: Value) -> Result<Value, String> {
        let bridge = self.bridge;
        // Its own local frame: a subscription update makes several calls with
        // large strings, freed as each returns.
        let reply = self.env.with_local_frame(8, |env| -> jni::errors::Result<Result<String, String>> {
            let name = env.new_string(method)?;
            let args = env.new_string(args.to_string())?;
            let result = env.call_method(
                bridge,
                jni_str!("call"),
                jni_sig!("(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                &[JValue::Object(&name), JValue::Object(&args)],
            );
            let reply = match result {
                Ok(value) => value.l()?,
                Err(jni::errors::Error::JavaException) => {
                    return Ok(Err(match env.exception_catch() {
                        Err(jni::errors::Error::CaughtJavaException { name, msg, .. }) => format!("{name}: {msg}"),
                        _ => "The kernel bridge failed".to_string(),
                    }));
                }
                Err(error) => return Err(error),
            };
            let reply = env.cast_local::<JString>(reply)?;
            if reply.is_null() {
                return Ok(Err("The kernel bridge returned nothing".to_string()));
            }
            Ok(Ok(reply.try_to_string(env)?))
        });
        let reply = reply.map_err(|e| format!("kernel {method}: {e}"))??;
        let mut reply: Value = serde_json::from_str(&reply).map_err(|e| format!("kernel {method}: {e}"))?;
        if let Some(message) = reply.get("error").and_then(Value::as_str) {
            return Err(message.to_string());
        }
        Ok(reply.get_mut("ok").map(Value::take).unwrap_or(Value::Null))
    }
}

/// Runs `f`; its error message becomes a RuntimeException with that message.
fn jni_string<'local>(
    env: &mut EnvUnowned<'local>,
    f: impl FnOnce(&mut jni::Env<'local>) -> jni::errors::Result<Result<String, String>>,
) -> JObject<'local> {
    env.with_env(|env| -> jni::errors::Result<JObject<'local>> {
        match f(env)? {
            Ok(value) => Ok(env.new_string(value)?.into()),
            Err(message) => {
                let _ = env.throw(message);
                Err(jni::errors::Error::JavaException)
            }
        }
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_io_nekohasekai_sfa_database_CoreStore_nativeOpen<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    store_path: JString<'local>,
    profiles_db: JString<'local>,
    settings_db: JString<'local>,
    ui_preferences: JString<'local>,
    webdav_preferences: JString<'local>,
    scratch_dir: JString<'local>,
) -> JObject<'local> {
    jni_string(&mut env, |env| {
        let path = PathBuf::from(store_path.try_to_string(env)?);
        let sources = Sources {
            profiles_db: profiles_db.try_to_string(env)?.into(),
            settings_db: settings_db.try_to_string(env)?.into(),
            ui_preferences: ui_preferences.try_to_string(env)?.into(),
            scratch_dir: scratch_dir.try_to_string(env)?.into(),
        };
        let webdav = PathBuf::from(webdav_preferences.try_to_string(env)?);
        Ok(open(path, sources, webdav))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_nekohasekai_sfa_database_CoreStore_nativeCall<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    method: JString<'local>,
    args: JString<'local>,
) -> JObject<'local> {
    jni_string(&mut env, |env| {
        let method = method.try_to_string(env)?;
        let args = args.try_to_string(env)?;
        Ok(call(&method, &args))
    })
}

#[no_mangle]
pub extern "system" fn Java_io_nekohasekai_sfa_database_CoreStore_nativeCallKernel<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    method: JString<'local>,
    args: JString<'local>,
    kernel: JObject<'local>,
) -> JObject<'local> {
    jni_string(&mut env, |env| {
        let method = method.try_to_string(env)?;
        let args = args.try_to_string(env)?;
        let Some(store) = store() else { return Ok(Err("The client store is not open".to_string())) };
        let mut kernel = JniKernel { env, bridge: &kernel };
        Ok(api::call_with_kernel(&store, &mut kernel, &method, &args))
    })
}
