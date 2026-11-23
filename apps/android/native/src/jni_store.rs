//! JNI surface of the mobile session's client store
//! ([`fkbox_mobile_ffi::session`]) for the Kotlin service layer
//! (`database/CoreStore.kt`): argument conversion only.
//!
//! Kotlin opens the store on first use (`nativeOpen`, with the app's paths);
//! the UI takes the same instance in `android_main` ([`store`]). libfkui.so
//! is loaded by `CoreStore` before the activity exists, so the service can
//! start (boot, tile) without the UI.
//!
//! Calls that need the cores (`nativeCallKernel`: subscription fetches and
//! profile content saves, `fkbox_client_core::content`) get the Kotlin
//! kernel bridge (`database/CoreKernel.kt`) with the call; Rust calls back
//! into it on the same thread, and it runs libbox in Go.

use std::path::PathBuf;

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::{jni_sig, jni_str, EnvUnowned};
use serde_json::Value;

use fkbox_client_core::content::Kernel;
use fkbox_client_core::legacy::Sources;
use fkbox_mobile_ffi::session;

pub use fkbox_mobile_ffi::session::store;

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
    store_dir: JString<'local>,
    profiles_db: JString<'local>,
    settings_db: JString<'local>,
    ui_preferences: JString<'local>,
    webdav_preferences: JString<'local>,
    scratch_dir: JString<'local>,
) -> JObject<'local> {
    jni_string(&mut env, |env| {
        let dir = PathBuf::from(store_dir.try_to_string(env)?);
        let sources = Sources {
            profiles_db: profiles_db.try_to_string(env)?.into(),
            settings_db: settings_db.try_to_string(env)?.into(),
            ui_preferences: ui_preferences.try_to_string(env)?.into(),
            scratch_dir: scratch_dir.try_to_string(env)?.into(),
        };
        let webdav = PathBuf::from(webdav_preferences.try_to_string(env)?);
        Ok(session::open(dir, sources, webdav))
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
        Ok(session::call(&method, &args))
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
        let mut kernel = JniKernel { env, bridge: &kernel };
        Ok(session::call_with_kernel(&mut kernel, &method, &args))
    })
}
