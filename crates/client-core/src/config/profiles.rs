//! The profile form's save (new and edited profiles), the import of a split
//! JSON array of configs as several profiles, and deleting a profile.
//!
//! These flows used to run in the Android host (`BoxProfiles.saveLocked`,
//! `saveAll`, `delete`). The cores' work (subscription fetch, array split,
//! conversion and validation) goes through the platform's [`Kernel`], as in
//! [`crate::content`]. An edit writes only the columns the form edits
//! ([`FormColumns`]), never the order, so a reorder made while the form was
//! open is kept.
//!
//! Applying a change to the running service stays with the platform: a save
//! returns `apply` when the selected profile's configuration changed and a
//! token; the platform finishes it (the service took it, or nothing needed
//! applying) or rolls it back (files, rows and the selection are put back).
//! Settings the flow writes (the selected profile) are returned as
//! [`Written`] entries for the platform's settings cache.

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::codec;
use crate::content::{self, Kernel, Prepared, Undo};
use crate::profile_array::{self, Entry};
use crate::service_settings::Written;
use crate::storage::{FormColumns, Profile, SettingValue, Store, CORES, CORE_AUTO, CORE_SING_BOX};
use crate::subscription::{self, Fetch, Metadata};

/// The settings key of the selected profile (`SettingsKey.SELECTED_PROFILE`).
pub const SELECTED_PROFILE_KEY: &str = "selected_profile";

/// The least automatic update interval a form accepts, in minutes.
pub const MIN_INTERVAL: i64 = 15;

/// What the profile form sends (its fields, and when editing the profile's
/// `id` and the `revision` the editor opened). Other keys of the form's
/// initial row are ignored.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Form {
    pub id: Option<i64>,
    pub name: Option<String>,
    pub url: Option<String>,
    pub interval: Option<Value>,
    pub core: Option<String>,
    pub content: Option<String>,
    pub revision: Option<String>,
    pub auto_update: Option<Value>,
    pub interval_edited: Option<Value>,
    pub updated_at: Option<Value>,
}

/// Where new configuration files go and the time of the save.
#[derive(Debug, Clone)]
pub struct Context {
    /// `<filesDir>/configs`.
    pub configs_dir: PathBuf,
    /// Unix milliseconds.
    pub now: i64,
}

/// The outcome of a save or an import.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Saved {
    /// The saved profiles, in order (one, unless a JSON array was split).
    pub ids: Vec<i64>,
    /// The selected profile's configuration changed: the platform applies
    /// it, then finishes or rolls back `token`.
    pub apply: bool,
    /// Settings written (the selection), for the platform's settings cache.
    pub written: Vec<Written>,
    /// Present when anything was written.
    pub token: Option<u64>,
}

/// The outcome of a delete.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Deleted {
    /// The deleted profile was the selected one: the platform applies the
    /// newly selected profile (or stops on none).
    pub apply: bool,
    pub written: Vec<Written>,
}

fn flag(value: &Option<Value>) -> bool {
    value.as_ref().and_then(Value::as_bool).unwrap_or(false)
}

fn number(value: &Option<Value>) -> Option<i64> {
    value.as_ref().and_then(|v| v.as_i64().or_else(|| v.as_f64().map(|f| f as i64)))
}

/// The selected profile id (-1 for none), as the platform's settings read it.
pub fn selected(store: &Store) -> Result<i64, String> {
    Ok(match store.setting(SELECTED_PROFILE_KEY).map_err(|e| e.0)? {
        Some(SettingValue::Long(id)) => id,
        _ => -1,
    })
}

fn select(store: &Store, id: i64) -> Result<Written, String> {
    let value = SettingValue::Long(id);
    store.put_setting(SELECTED_PROFILE_KEY, &value).map_err(|e| e.0)?;
    Ok(Written::of(SELECTED_PROFILE_KEY, &value))
}

fn core_enabled(store: &Store, key: &str) -> bool {
    !matches!(store.setting(key), Ok(Some(SettingValue::Bool(false))))
}

/// The core an empty "auto" profile starts on: sing-box, else mihomo, else
/// Xray, whichever is turned on first (`Settings.autoFetchCore`).
pub fn auto_core(store: &Store) -> &'static str {
    if core_enabled(store, "core_sing_box_enabled") {
        CORE_SING_BOX
    } else if core_enabled(store, "core_mihomo_enabled") {
        "mihomo"
    } else {
        "xray"
    }
}

/// What a new local profile without content starts with.
pub fn empty_content(core: &str) -> &'static str {
    match core {
        "xray" => "{\n  \"outbounds\": [\n    {\n      \"protocol\": \"freedom\",\n      \"tag\": \"direct\"\n    }\n  ]\n}\n",
        "mihomo" => "mode: rule\nproxies: []\nproxy-groups: []\nrules:\n  - MATCH,DIRECT\n",
        _ => "{}",
    }
}

fn check_url(url: &str) -> Result<(), String> {
    if subscription::is_http_url(url) {
        Ok(())
    } else {
        Err("Enter an HTTP or HTTPS subscription URL".into())
    }
}

fn check_core(core: &str) -> Result<(), String> {
    if CORES.contains(&core) {
        Ok(())
    } else {
        Err(format!("Unknown core: {core}"))
    }
}

/// Each element of a split array prepared for `core`; the first one its core
/// refuses fails the whole import, naming it.
fn prepare_entries(kernel: &mut dyn Kernel, core: &str, entries: Vec<Entry>) -> Result<Vec<Entry>, String> {
    entries
        .into_iter()
        .enumerate()
        .map(|(index, entry)| {
            let prepared = content::prepare(kernel, core, &entry.content)
                .map_err(|e| format!("Configuration {} ({}): {e}", index + 1, entry.name))?;
            Ok(Entry { content: prepared.content, core: prepared.core, ..entry })
        })
        .collect()
}

/// Everything one save or import wrote, to put back on rollback.
#[derive(Debug, Default)]
struct FormChange {
    /// Files written: their previous content (None: there was no file).
    files: Vec<(PathBuf, Option<String>)>,
    /// Profiles inserted.
    inserted: Vec<i64>,
    /// An edited profile's form columns before the save.
    edited: Option<(i64, FormColumns)>,
    /// The selection before the save, when the save changed it.
    selection: Option<i64>,
}

impl FormChange {
    fn is_empty(&self) -> bool {
        self.files.is_empty() && self.inserted.is_empty() && self.edited.is_none() && self.selection.is_none()
    }

    /// Puts everything back; returns the settings written doing so.
    fn revert(self, store: &Store) -> Result<Vec<Written>, String> {
        let mut errors = Vec::new();
        let mut written = Vec::new();
        for (path, previous) in self.files.into_iter().rev() {
            let result = match previous {
                Some(previous) => content::write_atomic(&path, &previous),
                None => content::remove(&path),
            };
            result.unwrap_or_else(|e| errors.push(e));
        }
        if !self.inserted.is_empty() {
            store.delete_profiles(&self.inserted).map(drop).unwrap_or_else(|e| errors.push(e.0));
        }
        if let Some((id, columns)) = &self.edited {
            match store.write_form_columns(*id, columns) {
                Ok(true) => {}
                Ok(false) => errors.push("Profile no longer exists".into()),
                Err(e) => errors.push(e.0),
            }
        }
        if let Some(previous) = self.selection {
            match select(store, previous) {
                Ok(entry) => written.push(entry),
                Err(e) => errors.push(e),
            }
        }
        if errors.is_empty() {
            Ok(written)
        } else {
            Err(errors.join("; "))
        }
    }
}

impl Undo for FormChange {
    fn undo(self: Box<Self>, store: &Store) -> Result<(), String> {
        (*self).revert(store).map(drop)
    }
}

/// What a new profile is created from.
struct New<'a> {
    name: String,
    url: &'a str,
    interval: i32,
    core: &'a str,
    auto_update: bool,
    interval_user_set: bool,
    prepared: Prepared,
    fetched: Option<&'a Fetch>,
    last_updated: i64,
}

fn insert_new(store: &Store, ctx: &Context, new: New, change: &mut FormChange, written: &mut Vec<Written>) -> Result<i64, String> {
    let extension = if new.prepared.core == "mihomo" { "yaml" } else { "json" };
    let path = ctx.configs_dir.join(format!("{}.{extension}", codec::random_uuid()));
    content::write_atomic(&path, &new.prepared.content)?;
    change.files.push((path.clone(), None));
    let metadata = match new.fetched {
        Some(fetch) if !new.url.is_empty() => Metadata::after(new.interval, new.interval_user_set, fetch),
        _ => Metadata { auto_update_interval: new.interval, ..Metadata::default() },
    };
    let profile = Profile {
        id: 0,
        user_order: store.next_profile_order().map_err(|e| e.0)?,
        name: new.name,
        icon: None,
        path: path.to_string_lossy().into_owned(),
        kind: (!new.url.is_empty()) as i64,
        remote_url: new.url.to_string(),
        last_updated: new.last_updated,
        auto_update: new.auto_update,
        auto_update_interval: metadata.auto_update_interval,
        core: new.core.to_string(),
        upload: metadata.upload,
        download: metadata.download,
        total: metadata.total,
        expire: metadata.expire,
        web_page_url: metadata.web_page_url,
        interval_user_set: new.interval_user_set,
        resolved_core: String::new(),
        resolved_stamp: String::new(),
    };
    let id = store.insert_profile(&profile).map_err(|e| e.0)?;
    change.inserted.push(id);
    if selected(store)? == -1 {
        written.push(select(store, id)?);
        change.selection = Some(-1);
    }
    Ok(id)
}

/// Keeps `change` for the platform when anything was written; on an error
/// part-way, puts back what was written instead.
fn conclude(store: &Store, result: Result<(Vec<i64>, bool), String>, change: FormChange, written: Vec<Written>) -> Result<Saved, String> {
    match result {
        Ok((ids, apply)) => {
            let token = (!change.is_empty()).then(|| store.pending.put(Box::new(change)));
            Ok(Saved { ids, apply, written, token })
        }
        Err(error) => Err(match change.revert(store) {
            Ok(_) => error,
            Err(undo) => format!("{error} (restoring failed: {undo})"),
        }),
    }
}

/// Saves the profile form: a new profile (from its content, its first
/// subscription fetch, or empty), or an edit of an existing one. A new
/// profile whose content is a JSON array of configs becomes one profile per
/// element (all following the same subscription URL); the first id leads
/// [`Saved::ids`].
pub fn save(store: &Store, kernel: &mut dyn Kernel, form: Form, ctx: &Context) -> Result<Saved, String> {
    let id = form.id.unwrap_or(0);
    let existing = if id == 0 { None } else { Some(store.profile(id).map_err(|e| e.0)?.ok_or("Profile no longer exists")?) };
    let name = form.name.as_deref().unwrap_or("").trim().to_string();
    if name.is_empty() {
        return Err("A profile name is required".into());
    }
    let url = form.url.as_deref().unwrap_or("").trim().to_string();
    if !url.is_empty() {
        check_url(&url)?;
    }
    let interval = number(&form.interval).unwrap_or(60);
    if interval < MIN_INTERVAL {
        return Err("Update interval must be at least 15 minutes".into());
    }
    let interval = interval.min(i32::MAX as i64) as i32;
    let core = form.core.clone().unwrap_or_else(|| existing.as_ref().map_or(CORE_AUTO.to_string(), |p| p.core.clone()));
    check_core(&core)?;
    let auto_update = !url.is_empty() && flag(&form.auto_update);
    let edited_interval = flag(&form.interval_edited);

    let Some(profile) = existing else {
        // A newly created remote profile takes the provider's headers from its first fetch.
        let fetched = if form.content.is_none() && !url.is_empty() { Some(content::fetch(kernel, &url, &core)?) } else { None };
        let incoming = match (&form.content, &fetched) {
            (Some(content), _) => content.clone(),
            (None, Some(fetch)) => fetch.content.clone(),
            // An empty local profile starts on its own core, or the first
            // core left on when auto (so turning sing-box off never blocks it).
            (None, None) => empty_content(if core == CORE_AUTO { auto_core(store) } else { &core }).to_string(),
        };
        let imported_at = number(&form.updated_at).filter(|t| *t >= 0);
        let last_updated = match imported_at {
            Some(at) if !url.is_empty() && form.content.is_some() => at,
            _ => ctx.now,
        };
        // A JSON array of configs becomes one profile per element; each
        // update of a shared subscription keeps following its own element.
        let entries = match content::split(kernel, &incoming)? {
            Some(elements) => prepare_entries(kernel, &core, profile_array::entries(&name, &elements))?,
            None => {
                let prepared = content::prepare(kernel, &core, &incoming)?;
                vec![Entry { name: name.clone(), content: prepared.content, core: prepared.core }]
            }
        };
        let mut change = FormChange::default();
        let mut written = Vec::new();
        let result = (|| {
            let mut ids = Vec::new();
            for entry in entries {
                let new = New {
                    name: entry.name,
                    url: &url,
                    interval,
                    core: &core,
                    auto_update,
                    interval_user_set: edited_interval,
                    prepared: Prepared { core: entry.core, content: entry.content },
                    fetched: fetched.as_ref(),
                    last_updated,
                };
                ids.push(insert_new(store, ctx, new, &mut change, &mut written)?);
            }
            let selected = selected(store)?;
            Ok((ids.clone(), ids.contains(&selected)))
        })();
        return conclude(store, result, change, written);
    };

    // An edit.
    let path = PathBuf::from(&profile.path);
    let previous = content::read(&path)?;
    let incoming = match &form.content {
        Some(content) => content.clone(),
        None => previous.clone().ok_or("The profile's configuration file is missing")?,
    };
    let prepared = content::prepare(kernel, &core, &incoming)?;
    // An edit that carries the revision it was opened at is refused when the
    // file changed since (another editor, a subscription update).
    if let Some(expected) = &form.revision {
        if previous.as_deref().map(content::revision).as_deref() != Some(expected.as_str()) {
            return Err("Configuration changed; reload it before saving".into());
        }
    }
    let before = store.form_columns(id).map_err(|e| e.0)?.ok_or("Profile no longer exists")?;
    let columns = FormColumns {
        name,
        core,
        kind: (!url.is_empty()) as i64,
        remote_url: url.clone(),
        auto_update,
        auto_update_interval: interval,
        // Edits keep a choice already made; the form reports whether the interval was touched.
        interval_user_set: profile.interval_user_set || edited_interval || interval != profile.auto_update_interval,
        // Traffic and expiry belonged to the old subscription; the next fetch reports the new one.
        metadata: (url.is_empty() || url != profile.remote_url).then(Metadata::default),
    };
    let mut change = FormChange::default();
    let changed = previous.as_deref() != Some(prepared.content.as_str());
    let result = (|| {
        if changed {
            content::write_atomic(&path, &prepared.content)?;
            change.files.push((path.clone(), previous.clone()));
        }
        if before != columns {
            if !store.write_form_columns(id, &columns).map_err(|e| e.0)? {
                return Err("Profile no longer exists".to_string());
            }
            change.edited = Some((id, before));
        }
        Ok((vec![id], changed && selected(store)? == id))
    })();
    conclude(store, result, change, Vec::new())
}

/// One profile of a split JSON array ([`save_all`]).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(default)]
pub struct ImportEntry {
    pub name: String,
    pub content: String,
}

/// Creates one local profile per entry of a split JSON array of configs.
/// Every entry is checked by its core first, so a bad element saves
/// nothing; returns the new ids in order.
pub fn save_all(store: &Store, kernel: &mut dyn Kernel, entries: Vec<ImportEntry>, core: &str, ctx: &Context) -> Result<Saved, String> {
    check_core(core)?;
    if entries.is_empty() {
        return Err("No configurations to import".into());
    }
    if let Some(index) = entries.iter().position(|e| e.name.trim().is_empty()) {
        return Err(format!("Configuration {} has no name", index + 1));
    }
    let entries = prepare_entries(
        kernel,
        core,
        entries.into_iter().map(|e| Entry { name: e.name.trim().to_string(), content: e.content, core: String::new() }).collect(),
    )?;
    let mut change = FormChange::default();
    let mut written = Vec::new();
    let result = (|| {
        let mut ids = Vec::new();
        for entry in entries {
            let new = New {
                name: entry.name,
                url: "",
                interval: 60,
                core,
                auto_update: false,
                interval_user_set: false,
                prepared: Prepared { core: entry.core, content: entry.content },
                fetched: None,
                last_updated: ctx.now,
            };
            ids.push(insert_new(store, ctx, new, &mut change, &mut written)?);
        }
        let selected = selected(store)?;
        Ok((ids.clone(), ids.contains(&selected)))
    })();
    conclude(store, result, change, written)
}

/// Deletes a profile and its file. Deleting the selected profile selects the
/// first one left (or none), which the platform then applies.
pub fn delete(store: &Store, id: i64) -> Result<Deleted, String> {
    let profile = store.profile(id).map_err(|e| e.0)?.ok_or("Profile no longer exists")?;
    store.delete_profiles(&[id]).map_err(|e| e.0)?;
    let _ = std::fs::remove_file(Path::new(&profile.path));
    if selected(store)? != id {
        return Ok(Deleted::default());
    }
    let next = store.profiles().map_err(|e| e.0)?.first().map_or(-1, |p| p.id);
    Ok(Deleted { apply: true, written: vec![select(store, next)?] })
}

/// The platform applied the save (or did not need to).
pub fn finish(store: &Store, token: u64) {
    store.pending.take(token);
}

/// The platform could not apply the save: everything it wrote is put back.
/// Returns the settings written putting it back. An unknown token does
/// nothing.
pub fn rollback(store: &Store, token: u64) -> Result<Vec<Written>, String> {
    let Some(change) = store.pending.take(token) else { return Ok(Vec::new()) };
    // Only form saves are rolled back through here; a content save's token
    // is rolled back by content::rollback.
    let selection_before = selected(store)?;
    change.undo(store)?;
    let selection_after = selected(store)?;
    Ok(if selection_after != selection_before { vec![Written::of(SELECTED_PROFILE_KEY, &SettingValue::Long(selection_after))] } else { Vec::new() })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::content::tests::{tempdir, FakeKernel};
    use crate::subscription::Response;
    use serde_json::json;

    fn ctx(dir: &tempdir::Dir) -> Context {
        Context { configs_dir: dir.0.join("configs"), now: 9_000 }
    }

    fn form(value: Value) -> Form {
        serde_json::from_value(value).unwrap()
    }

    fn file(store: &Store, id: i64) -> String {
        std::fs::read_to_string(store.profile(id).unwrap().unwrap().path).unwrap()
    }

    #[test]
    fn a_new_local_profile_is_written_selected_and_ordered_last() {
        let dir = tempdir::Dir::new();
        let store = Store::open_in_memory().unwrap();
        let first = store.insert_profile(&Profile { name: "x".into(), user_order: 4, ..Profile::default() }).unwrap();
        let mut kernel = FakeKernel::default();
        let saved = save(&store, &mut kernel, form(json!({"name": " a ", "content": " {} ", "core": "auto"})), &ctx(&dir)).unwrap();
        let id = saved.ids[0];
        let p = store.profile(id).unwrap().unwrap();
        assert_eq!((p.name.as_str(), p.user_order, p.last_updated, p.kind, p.core.as_str()), ("a", 5, 9_000, 0, "auto"));
        assert_eq!(file(&store, id), "{}");
        assert!(p.path.ends_with(".json"));
        // Nothing was selected: the new profile is, and applying it is the platform's.
        assert_eq!(selected(&store).unwrap(), id);
        assert!(saved.apply);
        assert_eq!(saved.written, vec![Written::of(SELECTED_PROFILE_KEY, &SettingValue::Long(id))]);
        // Rolling back removes the row, the file and the selection.
        let path = p.path.clone();
        assert_eq!(rollback(&store, saved.token.unwrap()).unwrap(), vec![Written::of(SELECTED_PROFILE_KEY, &SettingValue::Long(-1))]);
        assert!(store.profile(id).unwrap().is_none());
        assert!(!Path::new(&path).exists());
        assert!(store.profile(first).unwrap().is_some());
    }

    #[test]
    fn form_checks_come_first() {
        let dir = tempdir::Dir::new();
        let store = Store::open_in_memory().unwrap();
        let mut k = FakeKernel::default();
        let c = ctx(&dir);
        assert_eq!(save(&store, &mut k, form(json!({"name": " "})), &c).unwrap_err(), "A profile name is required");
        assert_eq!(save(&store, &mut k, form(json!({"name": "a", "url": "ftp://x"})), &c).unwrap_err(), "Enter an HTTP or HTTPS subscription URL");
        assert_eq!(save(&store, &mut k, form(json!({"name": "a", "interval": 5})), &c).unwrap_err(), "Update interval must be at least 15 minutes");
        assert_eq!(save(&store, &mut k, form(json!({"name": "a", "core": "v2"})), &c).unwrap_err(), "Unknown core: v2");
        assert_eq!(save(&store, &mut k, form(json!({"name": "a", "id": 99})), &c).unwrap_err(), "Profile no longer exists");
        assert_eq!(save(&store, &mut k, form(json!({"name": "a", "content": "bad"})), &c).unwrap_err(), "invalid configuration");
        assert!(store.profiles().unwrap().is_empty());
        assert!(k.calls.iter().all(|c| c != "fetch"));
    }

    #[test]
    fn an_empty_auto_profile_starts_on_the_first_core_left_on() {
        let store = Store::open_in_memory().unwrap();
        assert_eq!(auto_core(&store), "sing-box");
        store.put_setting("core_sing_box_enabled", &SettingValue::Bool(false)).unwrap();
        assert_eq!(auto_core(&store), "mihomo");
        store.put_setting("core_mihomo_enabled", &SettingValue::Bool(false)).unwrap();
        assert_eq!(auto_core(&store), "xray");
        let dir = tempdir::Dir::new();
        let saved = save(&store, &mut FakeKernel::default(), form(json!({"name": "e"})), &ctx(&dir)).unwrap();
        assert_eq!(file(&store, saved.ids[0]), empty_content("xray").trim());
    }

    #[test]
    fn a_new_subscription_takes_its_first_fetch_and_splits_arrays() {
        let dir = tempdir::Dir::new();
        let store = Store::open_in_memory().unwrap();
        store.put_setting(SELECTED_PROFILE_KEY, &SettingValue::Long(77)).unwrap();
        let mut kernel = FakeKernel {
            response: Response { content: "[alpha=a1|=b1]".into(), user_info: "total=10".into(), update_interval: "2".into(), ..Default::default() },
            ..Default::default()
        };
        let saved = save(
            &store,
            &mut kernel,
            form(json!({"name": "sub", "url": "https://e.com/s", "autoUpdate": true, "interval": 30})),
            &ctx(&dir),
        )
        .unwrap();
        assert_eq!(saved.ids.len(), 2);
        assert!(!saved.apply && saved.written.is_empty());
        let names: Vec<String> = saved.ids.iter().map(|id| store.profile(*id).unwrap().unwrap().name).collect();
        assert_eq!(names, ["alpha", "sub #2"]);
        let p = store.profile(saved.ids[1]).unwrap().unwrap();
        assert_eq!((p.total, p.auto_update_interval, p.auto_update, p.remote()), (10, 120, true, true));
        assert_eq!(file(&store, saved.ids[1]), "b1");
        // A user-chosen interval wins over the provider's hint.
        let saved = save(
            &store,
            &mut kernel,
            form(json!({"name": "s2", "url": "https://e.com/s", "interval": 30, "intervalEdited": true})),
            &ctx(&dir),
        )
        .unwrap();
        assert_eq!(store.profile(saved.ids[0]).unwrap().unwrap().auto_update_interval, 30);
    }

    #[test]
    fn imported_remote_content_keeps_its_update_time() {
        let dir = tempdir::Dir::new();
        let store = Store::open_in_memory().unwrap();
        let mut kernel = FakeKernel::default();
        let saved =
            save(&store, &mut kernel, form(json!({"name": "i", "url": "https://e.com", "content": "{}", "updatedAt": 1234})), &ctx(&dir)).unwrap();
        assert_eq!(store.profile(saved.ids[0]).unwrap().unwrap().last_updated, 1234);
        assert!(kernel.calls.iter().all(|c| c != "fetch"));
    }

    #[test]
    fn an_edit_writes_only_the_form_columns_and_keeps_the_order() {
        let dir = tempdir::Dir::new();
        let (store, _d, id) = crate::content::tests::setup(true, Some("old"));
        let other = store.insert_profile(&Profile { name: "o".into(), user_order: 1, ..Profile::default() }).unwrap();
        store.put_setting(SELECTED_PROFILE_KEY, &SettingValue::Long(id)).unwrap();
        // The list was reordered while the form was open.
        store.reorder_profiles(&[other, id]).unwrap();
        let revision = content::revision("old");
        let saved = save(
            &store,
            &mut FakeKernel::default(),
            form(json!({"id": id, "name": "renamed", "url": "https://example.com/s", "autoUpdate": true, "interval": 60,
                "core": "auto", "content": "new", "revision": revision})),
            &ctx(&dir),
        )
        .unwrap();
        assert!(saved.apply && saved.written.is_empty());
        let p = store.profile(id).unwrap().unwrap();
        assert_eq!((p.name.as_str(), p.user_order, p.last_updated, p.upload), ("renamed", 1, 1_000, 9));
        assert_eq!(p.web_page_url, "https://old.example");
        assert_eq!(file(&store, id), "new");
        // Rollback restores the file and the columns, still not the order.
        rollback(&store, saved.token.unwrap()).unwrap();
        let p = store.profile(id).unwrap().unwrap();
        assert_eq!((p.name.as_str(), p.user_order), ("sub", 1));
        assert_eq!(file(&store, id), "old");
    }

    #[test]
    fn an_edit_changing_the_url_clears_the_old_subscription_metadata() {
        let dir = tempdir::Dir::new();
        let (store, _d, id) = crate::content::tests::setup(true, Some("old"));
        let saved = save(
            &store,
            &mut FakeKernel::default(),
            form(json!({"id": id, "name": "sub", "url": "https://other.example/s", "interval": 90})),
            &ctx(&dir),
        )
        .unwrap();
        assert!(!saved.apply);
        let p = store.profile(id).unwrap().unwrap();
        assert_eq!((p.upload, p.web_page_url.as_str(), p.auto_update_interval, p.interval_user_set), (0, "", 90, true));
        // A stale revision is refused before anything is written.
        let err = save(&store, &mut FakeKernel::default(), form(json!({"id": id, "name": "x", "revision": "nope"})), &ctx(&dir)).unwrap_err();
        assert_eq!(err, "Configuration changed; reload it before saving");
        assert_eq!(store.profile(id).unwrap().unwrap().name, "sub");
    }

    #[test]
    fn import_checks_every_entry_before_saving_any() {
        let dir = tempdir::Dir::new();
        let store = Store::open_in_memory().unwrap();
        let entries = |second: &str| vec![ImportEntry { name: "a".into(), content: "{}".into() }, ImportEntry { name: "b".into(), content: second.into() }];
        let err = save_all(&store, &mut FakeKernel::default(), entries("bad"), "auto", &ctx(&dir)).unwrap_err();
        assert_eq!(err, "Configuration 2 (b): invalid configuration");
        assert!(store.profiles().unwrap().is_empty());
        assert_eq!(save_all(&store, &mut FakeKernel::default(), vec![], "auto", &ctx(&dir)).unwrap_err(), "No configurations to import");
        let saved = save_all(&store, &mut FakeKernel::default(), entries("{}"), "auto", &ctx(&dir)).unwrap();
        assert_eq!(saved.ids.len(), 2);
        assert!(saved.apply, "the first import selects its first profile");
        rollback(&store, saved.token.unwrap()).unwrap();
        assert!(store.profiles().unwrap().is_empty());
        assert_eq!(selected(&store).unwrap(), -1);
    }

    #[test]
    fn deleting_the_selected_profile_selects_the_first_left() {
        let (store, _d, id) = crate::content::tests::setup(false, Some("x"));
        let other = store.insert_profile(&Profile { name: "o".into(), user_order: 3, ..Profile::default() }).unwrap();
        assert_eq!(delete(&store, other).unwrap(), Deleted::default());
        let path = store.profile(id).unwrap().unwrap().path;
        store.put_setting(SELECTED_PROFILE_KEY, &SettingValue::Long(id)).unwrap();
        let third = store.insert_profile(&Profile { name: "t".into(), user_order: 9, ..Profile::default() }).unwrap();
        let deleted = delete(&store, id).unwrap();
        assert_eq!(deleted, Deleted { apply: true, written: vec![Written::of(SELECTED_PROFILE_KEY, &SettingValue::Long(third))] });
        assert!(!Path::new(&path).exists());
        assert_eq!(delete(&store, id).unwrap_err(), "Profile no longer exists");
        assert_eq!(delete(&store, third).unwrap().written, vec![Written::of(SELECTED_PROFILE_KEY, &SettingValue::Long(-1))]);
    }
}
