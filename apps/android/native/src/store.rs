//! The process's client store ([`fkbox_client_core::storage::Store`]) and its
//! JNI surface for the Kotlin service layer (`database/CoreStore.kt`).
//!
//! Kotlin opens the store on first use (`nativeOpen`, with the app's paths),
//! which also runs the one-time import of the Room databases; the UI takes
//! the same instance in `android_main` ([`store`]), so the process has one
//! connection to the file. libfkui.so is loaded by `CoreStore` before the
//! activity exists, so the service can start (boot, tile) without the UI.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JObject, JString};
use jni::EnvUnowned;

use fkbox_client_core::legacy::{self, Outcome, Sources};
use fkbox_client_core::{api, storage::Store};

static STORE: Mutex<Option<Arc<Store>>> = Mutex::new(None);

/// The store Kotlin opened, if it has.
pub fn store() -> Option<Arc<Store>> {
    STORE.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

/// Opens the store at `path` (once per process; later calls return the
/// summary of an import already recorded) and imports the earlier storage.
pub fn open(path: PathBuf, sources: Sources) -> Result<String, String> {
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
    Ok(match legacy::migrate(&store, &sources) {
        Ok(Outcome::AlreadyDone(summary)) => summary,
        Ok(Outcome::Done(summary)) => serde_json::to_string(&summary).unwrap_or_default(),
        Err(error) => serde_json::json!({ "status": "failed", "error": error.0 }).to_string(),
    })
}

fn call(method: &str, args: &str) -> Result<String, String> {
    let store = store().ok_or("The client store is not open")?;
    api::call(&store, method, args)
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
        Ok(open(path, sources))
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
