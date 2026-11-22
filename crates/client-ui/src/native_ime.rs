//! Text input through the platform's own text field. While a NativeTextInput
//! (ui/kit.slint) has focus, the Kotlin host lays an android.widget.EditText
//! over it (bridge/NativeEditor.kt): the cursor, selection, handles, context
//! menu and input connection are the system's, and every change comes back
//! here as an `ime` event that the field copies into its text. GameTextInput
//! is never configured (vendor/i-slint-backend-android-activity).
//!
//! Lengths go to the host in physical pixels; selections in UTF-16 units.

use std::cell::Cell;

use serde_json::{json, Value};
use slint::ComponentHandle;

use crate::app::{call, window};
use crate::{AppWindow, NativeIme, NativeImeSpec};

thread_local! {
    static NEXT_ID: Cell<i32> = const { Cell::new(0) };
}

pub fn host(method: &'static str, mut args: Value) {
    // The platform widgets take the UI's own colours.
    if matches!(method, "menuShow" | "dialogShow" | "textViewer") {
        if let (Some(map), Some(w)) = (args.as_object_mut(), window()) {
            let scheme = w.global::<crate::Theme>().get_s();
            map.insert("dark".into(), json!(crate::theme_apply::is_dark()));
            map.insert("primary".into(), json!(scheme.primary.as_argb_encoded()));
            map.insert("error".into(), json!(scheme.error.as_argb_encoded()));
            map.insert("surface".into(), json!(scheme.surface_container_high.as_argb_encoded()));
            map.insert("onSurface".into(), json!(scheme.on_surface.as_argb_encoded()));
            map.insert("onSurfaceVariant".into(), json!(scheme.on_surface_variant.as_argb_encoded()));
        }
    }
    call(method, args, move |result| {
        if let Err(error) = result {
            eprintln!("{method}: {error}");
        }
    });
}

pub fn scale() -> f32 {
    window().map(|w| w.window().scale_factor()).unwrap_or(1.0).max(0.1)
}

/// Menus, dialogs, messages and text selection go to the platform's own
/// widgets (src/nav.rs) where the host has them.
pub fn native_ui() -> bool {
    cfg!(target_os = "android")
}

/// The UTF-16 offset of a byte offset (clamped to the text and a character).
pub fn utf16_offset(text: &str, byte: i32) -> usize {
    let mut byte = (byte.max(0) as usize).min(text.len());
    while !text.is_char_boundary(byte) {
        byte -= 1;
    }
    text[..byte].encode_utf16().count()
}

pub fn install(w: &AppWindow) {
    let ime = w.global::<NativeIme>();
    #[cfg(target_os = "android")]
    {
        ime.set_enabled(true);
    }
    ime.on_begin(|spec: NativeImeSpec| {
        let id = NEXT_ID.with(|n| {
            n.set(n.get().wrapping_add(1).max(1));
            n.get()
        });
        let s = scale();
        if let Some(w) = window() {
            let ime = w.global::<NativeIme>();
            ime.set_text(spec.text.clone());
            ime.set_scroll_y(0.0);
            ime.set_active(id);
        }
        host(
            "imeBegin",
            json!({
                "id": id,
                "text": spec.text.as_str(),
                "x": spec.x * s,
                "y": spec.y * s,
                "width": spec.width * s,
                "height": spec.height * s,
                "multiline": spec.multiline,
                "wrap": spec.wrap,
                "align": spec.align,
                "valign": spec.valign,
                "mono": spec.mono,
                "fontSize": spec.font_size * s,
                "color": spec.color.as_argb_encoded(),
                "input": spec.input,
            }),
        );
        id
    });
    ime.on_frame(|id, x, y, width, height| {
        if !is_active(id) {
            return;
        }
        let s = scale();
        host("imeFrame", json!({ "id": id, "x": x * s, "y": y * s, "width": width * s, "height": height * s }));
    });
    ime.on_set_text(|id, text| {
        if !is_active(id) {
            return;
        }
        if let Some(w) = window() {
            w.global::<NativeIme>().set_text(text.clone());
        }
        host("imeText", json!({ "id": id, "text": text.as_str() }));
    });
    ime.on_set_selection(|id, start, end| {
        if !is_active(id) {
            return;
        }
        let text = window().map(|w| w.global::<NativeIme>().get_text()).unwrap_or_default();
        host("imeSelect", json!({ "id": id, "start": utf16_offset(&text, start), "end": utf16_offset(&text, end) }));
    });
    ime.on_end(|id| {
        if !is_active(id) {
            return;
        }
        if let Some(w) = window() {
            w.global::<NativeIme>().set_active(0);
        }
        host("imeEnd", json!({ "id": id }));
    });
}

fn is_active(id: i32) -> bool {
    id != 0 && window().map(|w| w.global::<NativeIme>().get_active() == id).unwrap_or(false)
}

/// `{"kind": "text" | "accept" | "end" | "scroll", "id": .., "text": .., "y": ..}`
/// from the host's editor; anything for an editor that is no longer the
/// active one is stale and dropped.
pub fn on_event(value: &Value) {
    let id = value["id"].as_i64().unwrap_or(0) as i32;
    if !is_active(id) {
        return;
    }
    let Some(w) = window() else { return };
    let ime = w.global::<NativeIme>();
    match value["kind"].as_str().unwrap_or("") {
        "text" => {
            ime.set_text(value["text"].as_str().unwrap_or("").into());
            ime.set_event_id(id);
            ime.set_text_tick(ime.get_text_tick().wrapping_add(1));
        }
        "accept" => {
            ime.set_event_id(id);
            ime.set_accept_tick(ime.get_accept_tick().wrapping_add(1));
        }
        "end" => ime.set_active(0),
        "scroll" => ime.set_scroll_y(value["y"].as_f64().unwrap_or(0.0) as f32 / scale()),
        _ => {}
    }
}
