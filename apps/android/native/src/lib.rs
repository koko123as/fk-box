//! FK-box on Android: the GameActivity entry point (`android_main`), the JNI
//! link to the Kotlin host (src/host.rs) around the Slint UI in
//! `fkbox-client-ui`, and the client store shared by the UI and the Kotlin
//! service layer (src/store.rs). Built into libfkui.so (BoxActivity's
//! `android.app.lib_name`, also loaded by `CoreStore` for the service).

#[cfg(target_os = "android")]
mod host;
pub mod store;

#[cfg(target_os = "android")]
use fkbox_client_ui::platform as ui;

#[cfg(target_os = "android")]
#[no_mangle]
fn android_main(android: i_slint_backend_android_activity::AndroidApp) {
    android_logger::init_once(android_logger::Config::default().with_tag("FKui").with_max_level(log::LevelFilter::Info));
    let files_dir = android.internal_data_path();
    install_panic_hook(files_dir.clone());
    if let Err(error) = host::init(android.vm_as_ptr(), android.activity_as_ptr()) {
        log::error!("host init failed: {error}");
        return;
    }
    ui::set_host(Box::new(host::AndroidHost));
    // The store Kotlin opens (CoreStore) before it answers the UI's `ready`.
    ui::set_store_provider(store::store);
    let platform = i_slint_backend_android_activity::AndroidPlatform::new(android);
    if let Err(error) = slint::platform::set_platform(Box::new(platform)) {
        log::error!("set_platform failed: {error}");
        return;
    }
    // Decides each touch's axis before Slint's swipe handler sees it.
    i_slint_backend_android_activity::fkbox_set_touch_observer(|phase, x, y| {
        let phase = match phase {
            0 => ui::Phase::Down,
            1 => ui::Phase::Move,
            2 => ui::Phase::Up,
            _ => ui::Phase::Cancel,
        };
        ui::touch(phase, x, y);
    });
    ui::install_fonts(files_dir);
    if let Err(error) = ui::run() {
        log::error!("UI stopped: {error}");
    }
    host::release();
}

/// Every UI panic goes to logcat (tag FKui) with its backtrace and to
/// `files/fkui-panic.txt`, the latest one, which the crash report export adds
/// as plain text (CrashReportManager.createZipArchive). Panics that a guard
/// catches are written too, so the file may describe one the app survived.
#[cfg(target_os = "android")]
fn install_panic_hook(files_dir: Option<std::path::PathBuf>) {
    let path = files_dir.map(|d| d.join("fkui-panic.txt"));
    std::panic::set_hook(Box::new(move |info| {
        let thread = std::thread::current().name().unwrap_or("unnamed").to_string();
        let seconds = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
        let backtrace = std::backtrace::Backtrace::force_capture();
        let text = format!("FKui panic (unix time {seconds}) on thread '{thread}':\n{info}\n\nbacktrace:\n{backtrace}\n");
        log::error!("{text}");
        if let Some(path) = &path {
            let _ = std::fs::write(path, &text);
        }
    }));
}
