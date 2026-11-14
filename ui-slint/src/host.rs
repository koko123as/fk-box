//! The JNI link to the Kotlin host (BoxActivity). Rust calls
//! `BoxActivity.slintCall(id, method, argsJson)`; Kotlin answers through the
//! `SlintHost.nativeReply` / `SlintHost.nativeEvent` natives below.

#[cfg(target_os = "android")]
mod android {
    use std::sync::Mutex;

    use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
    use jni::sys::{jboolean, jlong, jobject, JNI_TRUE};
    use jni::{JNIEnv, JavaVM};

    struct Host {
        vm: JavaVM,
        activity: GlobalRef,
    }

    static HOST: Mutex<Option<Host>> = Mutex::new(None);

    pub fn init(vm_ptr: *mut std::ffi::c_void, activity_ptr: *mut std::ffi::c_void) -> Result<(), String> {
        let vm = unsafe { JavaVM::from_raw(vm_ptr.cast()) }.map_err(|e| e.to_string())?;
        let activity = {
            let env = vm.attach_current_thread_permanently().map_err(|e| e.to_string())?;
            let object = unsafe { JObject::from_raw(activity_ptr as jobject) };
            env.new_global_ref(object).map_err(|e| e.to_string())?
        };
        *HOST.lock().unwrap() = Some(Host { vm, activity });
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
        let mut env = host.vm.attach_current_thread_permanently().map_err(|e| e.to_string())?;
        // The thread is attached permanently, so local references made here are
        // only freed with a frame: without one, two JStrings per call filled the
        // local reference table (512 entries, 256 calls) and aborted the process.
        let activity = host.activity.as_obj();
        let result: Result<(), jni::errors::Error> = env.with_local_frame(4, |env| {
            let method = env.new_string(method)?;
            let args = env.new_string(args)?;
            env.call_method(
                activity,
                "slintCall",
                "(JLjava/lang/String;Ljava/lang/String;)V",
                &[JValue::Long(id), JValue::Object(method.as_ref()), JValue::Object(args.as_ref())],
            )?;
            Ok(())
        });
        if result.is_err() {
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_describe();
                let _ = env.exception_clear();
            }
            return Err(crate::i18n::tr("Calling the app failed"));
        }
        Ok(())
    }

    /// Runs one host event or reply. A panic in its handler is logged instead
    /// of unwinding into the event loop, where android-activity would close
    /// the activity. Only the handler is skipped: the page state it touches is
    /// held in RefCells that are released on unwind, and the next event or
    /// refresh rebuilds what the handler would have shown.
    fn guarded(kind: &str, handler: impl FnOnce()) {
        if let Err(panic) = std::panic::catch_unwind(std::panic::AssertUnwindSafe(handler)) {
            let message = panic
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| panic.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown panic".into());
            log::error!("host {kind} handler panicked: {message}");
        }
    }

    fn read(env: &mut JNIEnv, value: &JString) -> String {
        env.get_string(value).map(Into::into).unwrap_or_default()
    }

    #[no_mangle]
    pub extern "system" fn Java_io_nekohasekai_sfa_bridge_SlintHost_nativeEvent(mut env: JNIEnv, _class: JClass, payload: JString) {
        let payload = read(&mut env, &payload);
        let _ = slint::invoke_from_event_loop(move || guarded("event", || crate::app::on_event(&payload)));
    }

    #[no_mangle]
    pub extern "system" fn Java_io_nekohasekai_sfa_bridge_SlintHost_nativeReply(
        mut env: JNIEnv,
        _class: JClass,
        id: jlong,
        ok: jboolean,
        payload: JString,
    ) {
        let payload = read(&mut env, &payload);
        let ok = ok == JNI_TRUE;
        let _ = slint::invoke_from_event_loop(move || guarded("reply", || crate::app::on_reply(id, ok, &payload)));
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
