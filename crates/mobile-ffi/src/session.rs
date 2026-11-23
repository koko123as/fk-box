//! The process's client store ([`Store`]) and its one-time imports.
//!
//! The platform host opens the store on first use ([`open`], with the app's
//! paths), which also runs the one-time import of the earlier storage; the UI
//! takes the same instance ([`store`]), so the process has one connection to
//! the file. On Android the service layer opens it before the activity
//! exists, so the service can start (boot, tile) without the UI.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use fkbox_client_core::content::Kernel;
use fkbox_client_core::ip_quality;
use fkbox_client_core::legacy::{self, Outcome, Sources};
use fkbox_client_core::storage::Store;

use crate::api;

static STORE: Mutex<Option<Arc<Store>>> = Mutex::new(None);

const NOT_OPEN: &str = "The client store is not open";

/// The store the host opened, if it has.
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
    let quality = match ip_quality::migrate(&store, &quality_xml, &sources.scratch_dir) {
        Ok(summary) => summary,
        Err(error) => serde_json::json!({ "status": "failed", "error": error.0 }).to_string(),
    };
    Ok(format!("{summary}; webdav: {webdav}; ipQuality: {quality}"))
}

/// One [`api`] call on the open store.
pub fn call(method: &str, args: &str) -> Result<String, String> {
    let store = store().ok_or(NOT_OPEN)?;
    api::call(&store, method, args)
}

/// One [`api`] call that may reach the cores through `kernel`.
pub fn call_with_kernel(kernel: &mut dyn Kernel, method: &str, args: &str) -> Result<String, String> {
    let store = store().ok_or(NOT_OPEN)?;
    api::call_with_kernel(&store, kernel, method, args)
}
