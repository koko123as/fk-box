//! The JNI link to the Kotlin host (BoxActivity). Rust calls
//! `BoxActivity.slintCall(id, method, argsJson)`; Kotlin answers through the
//! `SlintHost.nativeReply` / `SlintHost.nativeEvent` natives below.
//!
//! Uses the same `jni` version (0.22) as the Slint Android backend, so the
//! process has one JavaVM singleton and one copy of the crate.

#[cfg(target_os = "android")]
mod android {
    use std::sync::Mutex;

    use jni::errors::LogErrorAndDefault;
    use jni::objects::{JClass, JObject, JString, JValue};
    use jni::refs::Global;
    use jni::sys::{jboolean, jlong, jobject};
    use jni::{jni_sig, jni_str, EnvUnowned, JavaVM};

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

    pub fn invoke(id: i64, method: &str, args: &str) -> Result<(), String> {
        let guard = HOST.lock().unwrap_or_else(|e| e.into_inner());
        let host = guard.as_ref().ok_or_else(|| crate::i18n::tr("The interface is not connected to the app"))?;
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
            return Err(crate::i18n::tr("Calling the app failed"));
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
        let _ = slint::invoke_from_event_loop(move || crate::guarded("host event", || crate::app::on_event(&payload)));
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
        let _ = slint::invoke_from_event_loop(move || crate::guarded("host reply", || crate::app::on_reply(id, ok, &payload)));
    }
}

#[cfg(target_os = "android")]
pub use android::{init, invoke, release};

/// Off Android there is no Kotlin host; calls fail and the window shows the error.
#[cfg(all(not(target_os = "android"), not(feature = "shot")))]
pub fn invoke(_id: i64, _method: &str, _args: &str) -> Result<(), String> {
    Err("No Android host".into())
}

/// The screenshot harness answers the calls itself (src/shot.rs).
#[cfg(all(not(target_os = "android"), feature = "shot"))]
pub fn invoke(id: i64, method: &str, args: &str) -> Result<(), String> {
    crate::shot::queue(id, method, args);
    Ok(())
}
