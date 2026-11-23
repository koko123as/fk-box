//! The JNI link to the Kotlin host (BoxActivity), the UI's
//! `fkbox_mobile_ffi::NativeHost` on Android. Rust calls
//! `BoxActivity.slintCall(id, method, argsJson)`; Kotlin answers through the
//! `SlintHost.nativeReply` / `SlintHost.nativeEvent` natives below.
//!
//! Uses the same `jni` version (0.22) as the Slint Android backend, so the
//! process has one JavaVM singleton and one copy of the crate.

use std::sync::Mutex;

use jni::errors::LogErrorAndDefault;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::refs::Global;
use jni::sys::{jboolean, jlong, jobject};
use jni::{jni_sig, jni_str, EnvUnowned, JavaVM};

use fkbox_mobile_ffi::NativeHost as UiHost;
use fkbox_client_ui::platform as ui;

struct Host {
    vm: JavaVM,
    activity: Global<JObject<'static>>,
}

static HOST: Mutex<Option<Host>> = Mutex::new(None);

pub fn init(vm_ptr: *mut std::ffi::c_void, activity_ptr: *mut std::ffi::c_void) -> Result<(), String> {
    if vm_ptr.is_null() || activity_ptr.is_null() {
        return Err("no Java VM or activity".into());
    }
    // The JavaVM singleton the Slint backend initializes too.
    let vm = unsafe { JavaVM::from_raw(vm_ptr.cast()) };
    let activity = vm
        .attach_current_thread(|env| -> jni::errors::Result<_> {
            let object = unsafe { JObject::from_raw(env, activity_ptr as jobject) };
            env.new_global_ref(object)
        })
        .map_err(|e| e.to_string())?;
    *HOST.lock().unwrap_or_else(|e| e.into_inner()) = Some(Host { vm, activity });
    Ok(())
}

/// Drops the activity reference when the activity's native main returns
/// (the activity finished or was destroyed), so the finished Activity is
/// not kept alive by a JNI global reference. A later `init` (a new
/// activity in the same process) installs the new one.
pub fn release() {
    let host = HOST.lock().unwrap_or_else(|e| e.into_inner()).take();
    drop(host);
}

/// The Kotlin host as the UI's [UiHost]: every call goes to the activity
/// installed by [init].
pub struct AndroidHost;

impl UiHost for AndroidHost {
    fn invoke(&self, id: i64, method: &str, args: &str) -> Result<(), String> {
        invoke(id, method, args)
    }
}

pub fn invoke(id: i64, method: &str, args: &str) -> Result<(), String> {
    let guard = HOST.lock().unwrap_or_else(|e| e.into_inner());
    let host = guard.as_ref().ok_or_else(|| ui::tr("The interface is not connected to the app"))?;
    // attach_current_thread keeps the thread attached and runs the call in
    // its own local frame, so the two JStrings made per call are freed when
    // it returns (without a frame they filled the local reference table and
    // aborted the process). A Java exception is caught, cleared and
    // returned as an error.
    let result = host.vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        let method = env.new_string(method)?;
        let args = env.new_string(args)?;
        env.call_method(
            host.activity.as_obj(),
            jni_str!("slintCall"),
            jni_sig!("(JLjava/lang/String;Ljava/lang/String;)V"),
            &[JValue::Long(id), JValue::Object(&method), JValue::Object(&args)],
        )?;
        Ok(())
    });
    if let Err(error) = result {
        log::error!("slintCall {method} failed: {error}");
        return Err(ui::tr("Calling the app failed"));
    }
    Ok(())
}

fn read<'local>(env: &mut EnvUnowned<'local>, value: &JString<'local>) -> String {
    env.with_env(|env| -> jni::errors::Result<String> { value.try_to_string(env) }).resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_io_nekohasekai_sfa_bridge_SlintHost_nativeEvent<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    payload: JString<'local>,
) {
    let payload = read(&mut env, &payload);
    let _ = slint::invoke_from_event_loop(move || ui::guarded("host event", || ui::on_event(&payload)));
}

#[no_mangle]
pub extern "system" fn Java_io_nekohasekai_sfa_bridge_SlintHost_nativeReply<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    id: jlong,
    ok: jboolean,
    payload: JString<'local>,
) {
    let payload = read(&mut env, &payload);
    let _ = slint::invoke_from_event_loop(move || ui::guarded("host reply", || ui::on_reply(id, ok, &payload)));
}
