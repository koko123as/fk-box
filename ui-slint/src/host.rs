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

    pub fn invoke(id: i64, method: &str, args: &str) -> Result<(), String> {
        let guard = HOST.lock().unwrap();
        let host = guard.as_ref().ok_or("界面尚未连接到应用")?;
        let mut env = host.vm.attach_current_thread_permanently().map_err(|e| e.to_string())?;
        let method = env.new_string(method).map_err(|e| e.to_string())?;
        let args = env.new_string(args).map_err(|e| e.to_string())?;
        let result = env.call_method(
            host.activity.as_obj(),
            "slintCall",
            "(JLjava/lang/String;Ljava/lang/String;)V",
            &[JValue::Long(id), JValue::Object(method.as_ref()), JValue::Object(args.as_ref())],
        );
        if result.is_err() {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
            return Err("调用应用失败".into());
        }
        Ok(())
    }

    fn read(env: &mut JNIEnv, value: &JString) -> String {
        env.get_string(value).map(Into::into).unwrap_or_default()
    }

    #[no_mangle]
    pub extern "system" fn Java_io_nekohasekai_sfa_bridge_SlintHost_nativeEvent(mut env: JNIEnv, _class: JClass, payload: JString) {
        let payload = read(&mut env, &payload);
        let _ = slint::invoke_from_event_loop(move || crate::app::on_event(&payload));
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
        let _ = slint::invoke_from_event_loop(move || crate::app::on_reply(id, ok, &payload));
    }
}

#[cfg(target_os = "android")]
pub use android::{init, invoke};

/// Off Android there is no Kotlin host; calls fail and the window shows the error.
#[cfg(not(target_os = "android"))]
pub fn invoke(_id: i64, _method: &str, _args: &str) -> Result<(), String> {
    Err("No Android host".into())
}
