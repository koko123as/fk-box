//! BoxApp's theme: the seeded scheme from the appearance preferences
//! (accent or system colour, colour style, pure black), the light/dark/system
//! mode, the font family and the text scale (appTextScale).

use slint::{Color, ComponentHandle};

use crate::app::{data, long, truthy};
use crate::theme::{seeded_color_scheme, BoxScheme, ColorStyle};
use crate::{Scheme, Theme};

fn c(argb: u32) -> Color {
    Color::from_argb_encoded(argb)
}

pub fn to_slint(s: &BoxScheme) -> Scheme {
    Scheme {
        primary: c(s.primary),
        on_primary: c(s.on_primary),
        primary_container: c(s.primary_container),
        on_primary_container: c(s.on_primary_container),
        secondary: c(s.secondary),
        on_secondary: c(s.on_secondary),
        secondary_container: c(s.secondary_container),
        on_secondary_container: c(s.on_secondary_container),
        tertiary: c(s.tertiary),
        on_tertiary: c(s.on_tertiary),
        error: c(s.error),
        on_error: c(s.on_error),
        error_container: c(s.error_container),
        on_error_container: c(s.on_error_container),
        outline: c(s.outline),
        outline_variant: c(s.outline_variant),
        on_surface: c(s.on_surface),
        on_surface_variant: c(s.on_surface_variant),
        inverse_surface: c(s.inverse_surface),
        on_inverse_surface: c(s.on_inverse_surface),
        inverse_primary: c(s.inverse_primary),
        scrim: c(s.scrim),
        surface: c(s.surface),
        surface_container_low: c(s.surface_container_low),
        surface_container: c(s.surface_container),
        surface_container_high: c(s.surface_container_high),
        surface_container_highest: c(s.surface_container_highest),
        success: c(s.success),
        success_container: c(s.success_container),
        on_success_container: c(s.on_success_container),
        warning: c(s.warning),
        warning_container: c(s.warning_container),
        on_warning_container: c(s.on_warning_container),
    }
}

/// The accent colour the preferences pick (BoxApp `accent`).
pub fn accent() -> u32 {
    data(|d| {
        if d.pref("systemColor").as_bool() == Some(true) {
            d.system_accent
        } else {
            long(&d.pref("color")).map(|v| v as u32).unwrap_or(0xff007aff)
        }
    })
}

/// appTextScale: the custom scale when set, else the system one, within 0.8–1.4.
pub fn text_scale(system: f32) -> f32 {
    data(|d| {
        let custom = d.preferences.get("customFontScale").and_then(|v| v.as_bool()).unwrap_or(d.preferences.contains_key("fontScale"));
        let scale = if custom { d.pref("fontScale").as_f64().map(|v| v as f32).unwrap_or(1.0) } else { system };
        scale.clamp(0.8, 1.4)
    })
}

thread_local! {
    static LAST: std::cell::RefCell<String> = const { std::cell::RefCell::new(String::new()) };
    pub static SYSTEM_FONT_SCALE: std::cell::Cell<f32> = const { std::cell::Cell::new(1.0) };
}

pub fn apply() {
    let Some(w) = crate::app::window() else { return };
    let (mode, style, pure_black, font) = data(|d| {
        (
            d.pref_str("theme", "system"),
            d.pref_str("colorStyle", "vivid"),
            truthy(&d.pref("pureBlack")),
            d.pref_str("fontFamily", ""),
        )
    });
    let seed = accent();
    let key = format!("{seed}:{style}:{pure_black}");
    let changed = LAST.with(|l| {
        let mut l = l.borrow_mut();
        if *l == key {
            false
        } else {
            *l = key;
            true
        }
    });
    let theme = w.global::<Theme>();
    if changed {
        let style = ColorStyle::of(&style);
        theme.set_light_scheme(to_slint(&seeded_color_scheme(seed, false, style).to_pure_black(pure_black)));
        theme.set_dark_scheme(to_slint(&seeded_color_scheme(seed, true, style).to_pure_black(pure_black)));
    }
    theme.set_mode(mode.into());
    // A system family by its fonts.xml name; the bundled JetBrains Mono by its own.
    theme.set_font_family(if font == "JetBrainsMono" { "JetBrains Mono".into() } else { font.as_str().into() });
    theme.set_text_scale(text_scale(SYSTEM_FONT_SCALE.with(|s| s.get())));
}

/// Whether the dark scheme is showing (for the native pages opened over the UI).
pub fn is_dark() -> bool {
    crate::app::window().map(|w| w.global::<Theme>().get_dark()).unwrap_or(false)
}
