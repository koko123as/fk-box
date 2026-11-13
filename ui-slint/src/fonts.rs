//! The system fonts, as the Compose UI got them from the platform: the
//! device's default sans-serif face (a vendor font such as Flyme's included),
//! the face it falls back to for Chinese, and the default monospace face.
//!
//! fontique only scans /system/fonts and maps sans-serif to Roboto, so the
//! Kotlin side (bridge/SystemFonts.kt) asks Android's own text stack which
//! files it shapes "Ag", "中文" and monospace text with and writes them to
//! `files/fkui-fonts.json` before the native UI starts. They are registered
//! here memory-mapped (file-backed pages, not heap) as Slint's sans-serif and
//! system-ui families, with the Chinese face first for Han script fallback.

use std::sync::Arc;

use slint::fontique_011::fontique;

/// One face: a font file and its index in a collection.
#[derive(Clone, Debug, Default)]
pub struct Face {
    pub path: String,
    pub index: u32,
}

#[derive(Clone, Debug, Default)]
pub struct Spec {
    /// The default sans-serif faces (regular first, then other weights).
    pub sans: Vec<Face>,
    /// The faces Chinese text falls back to.
    pub cjk: Vec<Face>,
    pub mono: Vec<Face>,
}

thread_local! {
    static MONO: std::cell::RefCell<String> = const { std::cell::RefCell::new(String::new()) };
}

/// The monospace family name for `Theme.mono`; empty when none was found.
pub fn mono_family() -> String {
    MONO.with(|m| m.borrow().clone())
}

struct Mapped(memmap2::Mmap);

impl AsRef<[u8]> for Mapped {
    fn as_ref(&self) -> &[u8] {
        &self.0
    }
}

fn map(path: &str) -> Option<fontique::Blob<u8>> {
    let file = std::fs::File::open(path).ok()?;
    // Font files are read-only system files; the mapping lives as long as the blob.
    let mapped = unsafe { memmap2::Mmap::map(&file) }.ok()?;
    Some(fontique::Blob::new(Arc::new(Mapped(mapped))))
}

/// Registers the faces and returns their families, in order, without repeats.
fn families(collection: &mut fontique::Collection, faces: &[Face]) -> Vec<fontique::FamilyId> {
    let mut out = Vec::new();
    for face in faces {
        let Some(blob) = map(&face.path) else {
            log(&format!("font not readable: {}", face.path));
            continue;
        };
        let registered = collection.register_fonts(blob, None);
        let family = registered
            .iter()
            .find(|(_, infos)| infos.iter().any(|i| i.index() == face.index))
            .or_else(|| registered.first())
            .map(|(id, _)| *id);
        if let Some(id) = family {
            if !out.contains(&id) {
                out.push(id);
            }
        }
    }
    out
}

fn log(message: &str) {
    #[cfg(target_os = "android")]
    log::info!("{message}");
    #[cfg(not(target_os = "android"))]
    eprintln!("{message}");
}

pub fn apply(spec: &Spec) {
    let mut collection = slint::fontique_011::shared_collection();
    let sans = families(&mut collection, &spec.sans);
    let cjk = families(&mut collection, &spec.cjk);
    let mono = families(&mut collection, &spec.mono);
    let mut chain: Vec<fontique::FamilyId> = sans.clone();
    for id in &cjk {
        if !chain.contains(id) {
            chain.push(*id);
        }
    }
    if !chain.is_empty() {
        for generic in [fontique::GenericFamily::SansSerif, fontique::GenericFamily::SystemUi, fontique::GenericFamily::UiSansSerif] {
            collection.set_generic_families(generic, chain.iter().copied());
        }
    }
    if !cjk.is_empty() {
        // Han, Bopomofo and kana: the face Android picks for the device locale first.
        for script in [*b"Hani", *b"Hans", *b"Hant", *b"Bopo", *b"Hira", *b"Kana"] {
            let key = fontique::FallbackKey::new(fontique::Script::from_bytes(script), None);
            let rest: Vec<fontique::FamilyId> = collection.fallback_families(key).filter(|id| !cjk.contains(id)).collect();
            collection.set_fallbacks(key, cjk.iter().copied().chain(rest));
        }
    }
    if let Some(id) = mono.first() {
        collection.set_generic_families(fontique::GenericFamily::Monospace, std::iter::once(*id));
        let name = collection.family_name(*id).unwrap_or_default().to_string();
        MONO.with(|m| *m.borrow_mut() = name);
    }
    let names: Vec<String> = chain.iter().filter_map(|id| collection.family_name(*id).map(str::to_string)).collect();
    log(&format!("system fonts: sans/cjk {names:?}, mono {:?}", mono_family()));
}

fn faces(value: &serde_json::Value) -> Vec<Face> {
    value
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|f| {
                    Some(Face { path: f["path"].as_str()?.to_string(), index: f["index"].as_u64().unwrap_or(0) as u32 })
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Reads the spec SystemFonts.kt wrote.
pub fn read_spec(path: &std::path::Path) -> Option<Spec> {
    let text = std::fs::read_to_string(path).ok()?;
    let value: serde_json::Value = serde_json::from_str(&text).ok()?;
    Some(Spec { sans: faces(&value["sans"]), cjk: faces(&value["cjk"]), mono: faces(&value["mono"]) })
}

#[cfg(target_os = "android")]
pub fn install(files_dir: Option<std::path::PathBuf>) {
    match files_dir.map(|d| d.join("fkui-fonts.json")).and_then(|p| read_spec(&p)) {
        Some(spec) => apply(&spec),
        None => log("system fonts: no spec, using fontique defaults"),
    }
}

/// The harness: `SHOT_FONTS` is a JSON spec file like the one on Android.
#[cfg(not(target_os = "android"))]
pub fn install() {
    if let Some(spec) = std::env::var_os("SHOT_FONTS").and_then(|p| read_spec(std::path::Path::new(&p))) {
        apply(&spec);
    }
}
