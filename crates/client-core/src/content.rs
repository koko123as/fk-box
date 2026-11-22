//! Saving a profile's configuration: what an edit form sends back and what
//! a subscription update fetches.
//!
//! The flow (revision check, following a JSON array's element, atomic file
//! write, the row's update time and subscription metadata, rollback) runs
//! here. What only the cores can decide goes through a [`Kernel`], which the
//! platform implements over its kernel layer (on Android, libbox in Go
//! through the Kotlin bridge `database/CoreKernel.kt`): the HTTP fetch, the
//! split of a JSON array of configs, an element's remarks, and conversion
//! plus validation by each core's own parser.
//!
//! Applying a saved change to the running service is the platform's. A save
//! that may need it leaves a [`Pending`] change behind under a token; the
//! platform then either finishes it (the service took the change, or none
//! was needed) or rolls it back (the file and the row are put back, and the
//! platform restarts the service on the old configuration).

use std::collections::BTreeMap;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};

use crate::profile_array::{self, Element};
use crate::storage::{ContentState, Store};
use crate::subscription::{self, Fetch, Response};

/// The largest configuration a profile stores (as libbox and the editor allow).
pub const MAX_CONTENT_BYTES: usize = 16 * 1024 * 1024;

/// The platform's kernel layer. `call` runs one method with JSON arguments
/// and returns its JSON result; an `Err` carries the kernel's message.
///
/// Methods: `fetch {url, core}` → [`Response`]; `split {content}` → null or
/// a list of [`Element`]; `remarks {content}` → string; `prepare {core,
/// content}` → `{core, content}` (share links converted, the content checked
/// by its core's parser, the core resolved when `core` is "auto").
pub trait Kernel {
    fn call(&mut self, method: &str, args: Value) -> Result<Value, String>;
}

/// The content a core will run, and that core.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Prepared {
    pub core: String,
    pub content: String,
}

fn decode<T: for<'de> Deserialize<'de>>(method: &str, value: Value) -> Result<T, String> {
    serde_json::from_value(value).map_err(|e| format!("kernel {method}: unexpected result: {e}"))
}

/// Fetches a subscription through the kernel layer; `core` picks the
/// User-Agent so the provider answers in that core's format.
pub fn fetch(kernel: &mut dyn Kernel, url: &str, core: &str) -> Result<Fetch, String> {
    if !subscription::is_http_url(url.trim()) {
        return Err("Enter an HTTP or HTTPS subscription URL".into());
    }
    let response: Response = decode("fetch", kernel.call("fetch", json!({ "url": url, "core": core }))?)?;
    Ok(Fetch::parse(response))
}

/// The elements of a top-level JSON array of configs, None when `content`
/// is not one.
pub fn split(kernel: &mut dyn Kernel, content: &str) -> Result<Option<Vec<Element>>, String> {
    decode("split", kernel.call("split", json!({ "content": content }))?)
}

pub fn prepare(kernel: &mut dyn Kernel, core: &str, content: &str) -> Result<Prepared, String> {
    if content.len() > MAX_CONTENT_BYTES {
        return Err("Configuration exceeds 16 MiB".into());
    }
    let prepared: Prepared = decode("prepare", kernel.call("prepare", json!({ "core": core, "content": content }))?)?;
    if prepared.content.len() > MAX_CONTENT_BYTES {
        return Err("Configuration exceeds 16 MiB".into());
    }
    Ok(prepared)
}

/// A subscription that returns a JSON array of configs keeps each profile on
/// its own element ([`profile_array::select`]); other content is returned
/// unchanged.
pub fn follow_array(kernel: &mut dyn Kernel, profile_name: &str, previous: Option<&str>, content: String) -> Result<String, String> {
    let Some(mut elements) = split(kernel, &content)? else { return Ok(content) };
    // The saved content's remarks; content that has none (or that the
    // kernel cannot read any more) falls back to the profile's name.
    let saved = match previous {
        Some(previous) => kernel.call("remarks", json!({ "content": previous })).ok().and_then(|v| v.as_str().map(str::to_string)).unwrap_or_default(),
        None => String::new(),
    };
    let remarks: Vec<String> = elements.iter().map(|e| e.remarks.clone()).collect();
    let index = profile_array::select(&remarks, &saved, profile_name)
        .ok_or_else(|| format!("The subscription returned {} configurations and none matches this profile", elements.len()))?;
    Ok(elements.swap_remove(index).content)
}

/// The revision an edit form carries: SHA-256 of the content, lower-case hex.
pub fn revision(content: &str) -> String {
    Sha256::digest(content.as_bytes()).iter().map(|b| format!("{b:02x}")).collect()
}

/// A profile file as text (invalid UTF-8 replaced, as the platform reads
/// it), None when there is no file.
pub fn read(path: &Path) -> Result<Option<String>, String> {
    match fs::read(path) {
        Ok(bytes) => Ok(Some(String::from_utf8(bytes).unwrap_or_else(|e| String::from_utf8_lossy(e.as_bytes()).into_owned()))),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(format!("Unable to read {}: {e}", path.display())),
    }
}

/// Replaces `path` with `content` atomically: written and synced beside it,
/// then renamed over it, so a crash leaves either the old or the new file.
pub fn write_atomic(path: &Path, content: &str) -> Result<(), String> {
    write_atomic_bytes(path, content.as_bytes())
}

/// [`write_atomic`] for any bytes (a file put back as it was).
pub fn write_atomic_bytes(path: &Path, content: &[u8]) -> Result<(), String> {
    let fail = |e: std::io::Error| format!("Unable to write {}: {e}", path.display());
    let parent = path.parent().filter(|p| !p.as_os_str().is_empty()).unwrap_or(Path::new("."));
    fs::create_dir_all(parent).map_err(fail)?;
    let mut name = path.file_name().ok_or_else(|| format!("Invalid profile path: {}", path.display()))?.to_os_string();
    name.push(".fkbox-new");
    let temporary = parent.join(name);
    let result = (|| {
        let mut file = fs::File::create(&temporary)?;
        file.write_all(content)?;
        file.sync_all()?;
        drop(file);
        fs::rename(&temporary, path)?;
        // The rename is durable once the directory is synced (best effort).
        if let Ok(dir) = fs::File::open(parent) {
            let _ = dir.sync_all();
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result.map_err(fail)
}

fn remove(path: &Path) -> Result<(), String> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(format!("Unable to restore profile file: {e}")),
    }
}

/// What to put back if the platform could not apply a save.
#[derive(Debug)]
struct Change {
    id: i64,
    path: PathBuf,
    /// The file before the save, when the save wrote it (None: there was no file).
    file: Option<Option<String>>,
    /// The row's [`ContentState`] before the save, when the save changed it.
    state: Option<ContentState>,
}

impl Change {
    fn undo(self, store: &Store) -> Result<(), String> {
        let mut errors = Vec::new();
        match self.file {
            Some(Some(previous)) => write_atomic(&self.path, &previous).unwrap_or_else(|e| errors.push(e)),
            Some(None) => remove(&self.path).unwrap_or_else(|e| errors.push(e)),
            None => {}
        }
        if let Some(state) = &self.state {
            match store.restore_content_state(self.id, state) {
                Ok(true) => {}
                Ok(false) => errors.push("Profile no longer exists".into()),
                Err(e) => errors.push(e.0),
            }
        }
        if errors.is_empty() {
            Ok(())
        } else {
            Err(errors.join("; "))
        }
    }
}

/// Saves waiting for the platform to finish or roll them back. Abandoned
/// ones (a platform that never answered) are dropped oldest first.
#[derive(Debug, Default)]
pub struct Pending {
    next: AtomicU64,
    changes: Mutex<BTreeMap<u64, Change>>,
}

const MAX_PENDING: usize = 16;

impl Pending {
    fn put(&self, change: Change) -> u64 {
        let token = self.next.fetch_add(1, Ordering::SeqCst) + 1;
        let mut changes = self.changes.lock().unwrap_or_else(|e| e.into_inner());
        while changes.len() >= MAX_PENDING {
            changes.pop_first();
        }
        changes.insert(token, change);
        token
    }

    fn take(&self, token: u64) -> Option<Change> {
        self.changes.lock().unwrap_or_else(|e| e.into_inner()).remove(&token)
    }
}

/// One save of a profile's configuration.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Replace {
    pub id: i64,
    /// The new content; ignored for a subscription update, which fetches it.
    pub content: Option<String>,
    /// Fetch the profile's subscription and save what it returns, with the
    /// provider's metadata.
    pub subscription: bool,
    /// Refuse the save when the file is no longer at this revision.
    pub expected_revision: Option<String>,
    /// Record `now` as the update time even when nothing changed.
    pub touch: bool,
    /// The profile is the selected one (a change then needs applying).
    pub selected: bool,
    /// Unix milliseconds.
    pub now: i64,
}

/// The outcome of a [`Replace`].
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Replaced {
    /// The file's content changed.
    pub changed: bool,
    /// The change is to the selected profile: the platform applies it to the
    /// running service, then finishes or rolls back `token`.
    pub apply: bool,
    /// The automatic update interval changed (the platform reschedules).
    pub interval_changed: bool,
    /// Present when anything was written; pass it to [`finish`] or [`rollback`].
    pub token: Option<u64>,
}

/// Runs a [`Replace`]: a failure part-way puts back what was written.
pub fn replace(store: &Store, kernel: &mut dyn Kernel, request: Replace) -> Result<Replaced, String> {
    let profile = store.profile(request.id).map_err(|e| e.0)?.ok_or("Profile no longer exists")?;
    if request.subscription && !profile.remote() {
        return Err("Only remote profiles can be updated".into());
    }
    let path = PathBuf::from(&profile.path);
    let previous = read(&path)?;
    if let Some(expected) = &request.expected_revision {
        if previous.as_deref().map(revision).as_deref() != Some(expected.as_str()) {
            return Err("Configuration changed; reload it before saving".into());
        }
    }
    let (incoming, fetched) = if request.subscription {
        let fetched = fetch(kernel, &profile.remote_url, &profile.core)?;
        let content = follow_array(kernel, &profile.name, previous.as_deref(), fetched.content.clone())?;
        (content, Some(fetched))
    } else {
        (request.content.clone().ok_or("Missing argument: content")?, None)
    };
    let cleaned = prepare(kernel, &profile.core, &incoming)?.content;
    let changed = previous.as_deref() != Some(cleaned.as_str());
    if !changed && !request.touch && fetched.is_none() {
        return Ok(Replaced::default());
    }
    let saved_at = if request.touch || (changed && !profile.remote()) { Some(request.now) } else { None };

    let mut change = Change { id: profile.id, path, file: None, state: None };
    if changed {
        write_atomic(&change.path, &cleaned)?;
        change.file = Some(previous);
    }
    let mut interval_changed = false;
    if saved_at.is_some() || fetched.is_some() {
        let recorded = match store.record_content_save(profile.id, saved_at, fetched.as_ref()) {
            Ok(Some(states)) => Ok(states),
            Ok(None) => Err("Profile no longer exists".to_string()),
            Err(e) => Err(e.0),
        };
        match recorded {
            Ok((before, after)) => {
                interval_changed = before.metadata.auto_update_interval != after.metadata.auto_update_interval;
                if before != after {
                    change.state = Some(before);
                }
            }
            Err(error) => {
                return Err(match change.undo(store) {
                    Ok(()) => error,
                    Err(undo) => format!("{error} (restoring failed: {undo})"),
                });
            }
        }
    }
    let token = (change.file.is_some() || change.state.is_some()).then(|| store.pending.put(change));
    Ok(Replaced { changed, apply: changed && request.selected, interval_changed, token })
}

/// The platform applied the save (or did not need to): forget how to undo it.
pub fn finish(store: &Store, token: u64) {
    store.pending.take(token);
}

/// The platform could not apply the save: put the file and the row back.
/// An unknown token (already finished) does nothing.
pub fn rollback(store: &Store, token: u64) -> Result<(), String> {
    match store.pending.take(token) {
        Some(change) => change.undo(store),
        None => Ok(()),
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::storage::Profile;

    /// A kernel without cores: `prepare` trims and refuses "bad", `split`
    /// reads a "[a|b]" list of `remarks=content` elements.
    #[derive(Default)]
    pub(crate) struct FakeKernel {
        pub response: Response,
        pub calls: Vec<String>,
        pub fail_fetch: bool,
    }

    impl Kernel for FakeKernel {
        fn call(&mut self, method: &str, args: Value) -> Result<Value, String> {
            self.calls.push(method.into());
            let content = args["content"].as_str().unwrap_or("").to_string();
            match method {
                "fetch" if self.fail_fetch => Err("connection refused".into()),
                "fetch" => Ok(serde_json::to_value(&self.response).unwrap()),
                "split" => Ok(match content.strip_prefix('[').and_then(|c| c.strip_suffix(']')) {
                    Some(list) => serde_json::to_value(
                        list.split('|')
                            .map(|e| {
                                let (remarks, content) = e.split_once('=').unwrap_or(("", e));
                                Element { remarks: remarks.into(), content: content.into(), core: "xray".into() }
                            })
                            .collect::<Vec<_>>(),
                    )
                    .unwrap(),
                    None => Value::Null,
                }),
                "remarks" => Ok(Value::String(content.split_once(':').map(|(r, _)| r.to_string()).unwrap_or_default())),
                "prepare" if content.contains("bad") => Err("invalid configuration".into()),
                "prepare" => Ok(json!({ "core": "sing-box", "content": content.trim() })),
                _ => Err(format!("unknown {method}")),
            }
        }
    }

    pub(crate) fn setup(remote: bool, content: Option<&str>) -> (Store, tempdir::Dir, i64) {
        let dir = tempdir::Dir::new();
        let path = dir.0.join("configs/p.json");
        if let Some(content) = content {
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(&path, content).unwrap();
        }
        let store = Store::open_in_memory().unwrap();
        let profile = Profile {
            name: "sub".into(),
            path: path.to_string_lossy().into(),
            kind: remote as i64,
            remote_url: if remote { "https://example.com/s".into() } else { String::new() },
            last_updated: 1_000,
            auto_update: remote,
            upload: 9,
            web_page_url: "https://old.example".into(),
            ..Profile::default()
        };
        let id = store.insert_profile(&profile).unwrap();
        (store, dir, id)
    }

    pub(crate) mod tempdir {
        pub struct Dir(pub std::path::PathBuf);
        impl Dir {
            pub fn new() -> Dir {
                static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
                let n = NEXT.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                let path = std::env::temp_dir().join(format!("fkbox-content-{}-{n}", std::process::id()));
                let _ = std::fs::remove_dir_all(&path);
                std::fs::create_dir_all(&path).unwrap();
                Dir(path)
            }
        }
        impl Drop for Dir {
            fn drop(&mut self) {
                let _ = std::fs::remove_dir_all(&self.0);
            }
        }
    }

    fn file(store: &Store, id: i64) -> Option<String> {
        read(Path::new(&store.profile(id).unwrap().unwrap().path)).unwrap()
    }

    fn edit(id: i64, content: &str, expected: Option<String>) -> Replace {
        Replace { id, content: Some(content.into()), expected_revision: expected, selected: true, now: 5_000, ..Default::default() }
    }

    #[test]
    fn revision_is_sha256_hex() {
        assert_eq!(revision(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assert_eq!(revision("{}").len(), 64);
    }

    #[test]
    fn an_edit_checks_the_revision_writes_and_stamps_local_profiles() {
        let (store, _dir, id) = setup(false, Some("old"));
        let mut kernel = FakeKernel::default();
        assert_eq!(
            replace(&store, &mut kernel, edit(id, "new", Some(revision("other")))).unwrap_err(),
            "Configuration changed; reload it before saving"
        );
        let done = replace(&store, &mut kernel, edit(id, " new ", Some(revision("old")))).unwrap();
        assert!(done.changed && done.apply && !done.interval_changed && done.token.is_some());
        assert_eq!(file(&store, id).as_deref(), Some("new"));
        assert_eq!(store.profile(id).unwrap().unwrap().last_updated, 5_000);
        finish(&store, done.token.unwrap());
        // Unchanged content without touch writes nothing.
        assert_eq!(replace(&store, &mut kernel, edit(id, "new", None)).unwrap(), Replaced::default());
        // The core refuses: nothing is written.
        assert_eq!(replace(&store, &mut kernel, edit(id, "bad", None)).unwrap_err(), "invalid configuration");
        assert_eq!(file(&store, id).as_deref(), Some("new"));
    }

    #[test]
    fn rollback_puts_the_file_and_the_row_back() {
        let (store, _dir, id) = setup(false, Some("old"));
        let done = replace(&store, &mut FakeKernel::default(), edit(id, "new", None)).unwrap();
        rollback(&store, done.token.unwrap()).unwrap();
        assert_eq!(file(&store, id).as_deref(), Some("old"));
        assert_eq!(store.profile(id).unwrap().unwrap().last_updated, 1_000);
        // A second rollback (or a finish) of the same token does nothing.
        rollback(&store, done.token.unwrap()).unwrap();
    }

    #[test]
    fn rollback_removes_a_file_that_did_not_exist() {
        let (store, _dir, id) = setup(false, None);
        let done = replace(&store, &mut FakeKernel::default(), edit(id, "new", None)).unwrap();
        assert_eq!(file(&store, id).as_deref(), Some("new"));
        rollback(&store, done.token.unwrap()).unwrap();
        assert_eq!(file(&store, id), None);
    }

    fn update(id: i64) -> Replace {
        Replace { id, subscription: true, touch: true, selected: false, now: 7_000, ..Default::default() }
    }

    #[test]
    fn a_subscription_update_saves_content_and_metadata() {
        let (store, _dir, id) = setup(true, Some("old"));
        let mut kernel = FakeKernel {
            response: Response { content: "fresh".into(), user_info: "upload=1; total=10".into(), update_interval: "2".into(), web_page: "javascript:x".into() },
            ..Default::default()
        };
        let done = replace(&store, &mut kernel, update(id)).unwrap();
        assert!(done.changed && !done.apply && done.interval_changed);
        let profile = store.profile(id).unwrap().unwrap();
        assert_eq!((profile.upload, profile.total, profile.web_page_url.as_str()), (1, 10, ""));
        assert_eq!((profile.auto_update_interval, profile.last_updated), (120, 7_000));
        assert_eq!(file(&store, id).as_deref(), Some("fresh"));
        rollback(&store, done.token.unwrap()).unwrap();
        let profile = store.profile(id).unwrap().unwrap();
        assert_eq!((profile.upload, profile.auto_update_interval, profile.last_updated), (9, 60, 1_000));
        assert_eq!(profile.web_page_url, "https://old.example");
        assert_eq!(file(&store, id).as_deref(), Some("old"));
    }

    #[test]
    fn a_failed_fetch_or_local_profile_changes_nothing() {
        let (store, _dir, id) = setup(true, Some("old"));
        let mut kernel = FakeKernel { fail_fetch: true, ..Default::default() };
        assert_eq!(replace(&store, &mut kernel, update(id)).unwrap_err(), "connection refused");
        assert_eq!(store.profile(id).unwrap().unwrap().last_updated, 1_000);
        let (store, _dir, id) = setup(false, Some("old"));
        assert_eq!(replace(&store, &mut FakeKernel::default(), update(id)).unwrap_err(), "Only remote profiles can be updated");
        assert_eq!(replace(&store, &mut FakeKernel::default(), update(id + 1)).unwrap_err(), "Profile no longer exists");
    }

    #[test]
    fn an_unchanged_subscription_still_records_the_time_and_metadata() {
        let (store, _dir, id) = setup(true, Some("same"));
        let mut kernel = FakeKernel { response: Response { content: "same".into(), ..Default::default() }, ..Default::default() };
        let done = replace(&store, &mut kernel, Replace { selected: true, ..update(id) }).unwrap();
        assert!(!done.changed && !done.apply && !done.interval_changed);
        let profile = store.profile(id).unwrap().unwrap();
        assert_eq!((profile.last_updated, profile.upload, profile.web_page_url.as_str()), (7_000, 0, ""));
    }

    #[test]
    fn a_subscription_array_follows_the_profiles_element() {
        let (store, _dir, id) = setup(true, Some("beta:old"));
        let mut kernel = FakeKernel { response: Response { content: "[alpha=a1|beta=b1]".into(), ..Default::default() }, ..Default::default() };
        replace(&store, &mut kernel, update(id)).unwrap();
        assert_eq!(file(&store, id).as_deref(), Some("b1"));
        // Neither the saved remarks ("" for "b1") nor the name match any element.
        kernel.response.content = "[x=1|y=2]".into();
        assert_eq!(
            replace(&store, &mut kernel, update(id)).unwrap_err(),
            "The subscription returned 2 configurations and none matches this profile"
        );
        assert_eq!(file(&store, id).as_deref(), Some("b1"));
    }

    #[test]
    fn a_user_chosen_interval_is_kept_against_the_provider() {
        let (store, _dir, id) = setup(true, Some("old"));
        let mut profile = store.profile(id).unwrap().unwrap();
        profile.interval_user_set = true;
        profile.auto_update_interval = 30;
        store.update_profile(&profile, false).unwrap();
        let mut kernel = FakeKernel { response: Response { content: "x".into(), update_interval: "24".into(), ..Default::default() }, ..Default::default() };
        let done = replace(&store, &mut kernel, update(id)).unwrap();
        assert!(!done.interval_changed);
        assert_eq!(store.profile(id).unwrap().unwrap().auto_update_interval, 30);
    }

    #[test]
    fn background_updates_never_touch_the_order_or_other_edits() {
        // A reorder (and a rename) lands between the update's read of the
        // profile and its write; the update writes only its own columns.
        let (store, _dir, id) = setup(true, Some("old"));
        let other = store.insert_profile(&Profile { name: "other".into(), user_order: 1, ..Profile::default() }).unwrap();
        struct Racing<'a> {
            inner: FakeKernel,
            store: &'a Store,
            id: i64,
            other: i64,
        }
        impl Kernel for Racing<'_> {
            fn call(&mut self, method: &str, args: Value) -> Result<Value, String> {
                if method == "fetch" {
                    self.store.reorder_profiles(&[self.other, self.id]).unwrap();
                    let mut p = self.store.profile(self.id).unwrap().unwrap();
                    p.name = "renamed".into();
                    self.store.update_profile(&p, false).unwrap();
                }
                self.inner.call(method, args)
            }
        }
        let mut kernel = Racing {
            inner: FakeKernel { response: Response { content: "new".into(), user_info: "total=5".into(), ..Default::default() }, ..Default::default() },
            store: &store,
            id,
            other,
        };
        let done = replace(&store, &mut kernel, update(id)).unwrap();
        let ids: Vec<i64> = store.profiles().unwrap().iter().map(|p| p.id).collect();
        assert_eq!(ids, vec![other, id]);
        let profile = store.profile(id).unwrap().unwrap();
        assert_eq!((profile.name.as_str(), profile.total, profile.user_order), ("renamed", 5, 1));
        // Rolling back restores only the update's columns too.
        rollback(&store, done.token.unwrap()).unwrap();
        let profile = store.profile(id).unwrap().unwrap();
        assert_eq!((profile.name.as_str(), profile.total, profile.user_order, profile.last_updated), ("renamed", 0, 1, 1_000));
    }

    #[test]
    fn abandoned_changes_are_dropped_oldest_first() {
        let pending = Pending::default();
        let first = pending.put(Change { id: 1, path: PathBuf::new(), file: None, state: None });
        for _ in 0..MAX_PENDING {
            pending.put(Change { id: 2, path: PathBuf::new(), file: None, state: None });
        }
        assert!(pending.take(first).is_none());
        assert_eq!(pending.changes.lock().unwrap().len(), MAX_PENDING);
    }

    #[test]
    fn atomic_write_replaces_and_leaves_no_temporary() {
        let dir = tempdir::Dir::new();
        let path = dir.0.join("a/b.json");
        write_atomic(&path, "one").unwrap();
        write_atomic(&path, "two").unwrap();
        assert_eq!(fs::read_to_string(&path).unwrap(), "two");
        assert_eq!(fs::read_dir(path.parent().unwrap()).unwrap().count(), 1);
        assert_eq!(read(&dir.0.join("missing")).unwrap(), None);
        fs::write(&path, [0x66, 0xff]).unwrap();
        assert_eq!(read(&path).unwrap().unwrap(), "f\u{fffd}");
    }
}
