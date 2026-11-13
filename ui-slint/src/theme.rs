//! The seeded colour scheme (compose/theme/Color.kt, adapted from FlClash,
//! GPL-3.0): Material Color Utilities' dynamic scheme (Content for vivid and
//! tinted, TonalSpot for soft, Monochrome) with FK-box's neutral, accent and
//! surface adjustments, and the pure black variant.

use material_colors::color::Rgb;
use material_colors::dynamic_color::{DynamicScheme, MaterialDynamicColors};
use material_colors::hct::Hct;
use material_colors::palette::TonalPalette;
use material_colors::scheme::variant::{SchemeContent, SchemeMonochrome, SchemeTonalSpot};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ColorStyle {
    Vivid,
    Tinted,
    Soft,
    Monochrome,
}

impl ColorStyle {
    pub fn of(name: &str) -> Self {
        match name.to_ascii_lowercase().as_str() {
            "tinted" => Self::Tinted,
            "soft" => Self::Soft,
            "monochrome" => Self::Monochrome,
            _ => Self::Vivid,
        }
    }
}

/// ARGB colours of one scheme, named after BoxScheme.
#[derive(Clone, Copy, Debug, Default)]
pub struct BoxScheme {
    pub dark: bool,
    pub primary: u32,
    pub on_primary: u32,
    pub primary_container: u32,
    pub on_primary_container: u32,
    pub secondary: u32,
    pub on_secondary: u32,
    pub secondary_container: u32,
    pub on_secondary_container: u32,
    pub tertiary: u32,
    pub on_tertiary: u32,
    pub error: u32,
    pub on_error: u32,
    pub error_container: u32,
    pub on_error_container: u32,
    pub outline: u32,
    pub outline_variant: u32,
    pub on_surface: u32,
    pub on_surface_variant: u32,
    pub inverse_surface: u32,
    pub on_inverse_surface: u32,
    pub inverse_primary: u32,
    pub scrim: u32,
    pub surface: u32,
    pub surface_bright: u32,
    pub surface_dim: u32,
    pub surface_container_lowest: u32,
    pub surface_container_low: u32,
    pub surface_container: u32,
    pub surface_container_high: u32,
    pub surface_container_highest: u32,
    pub success: u32,
    pub success_container: u32,
    pub on_success_container: u32,
    pub warning: u32,
    pub warning_container: u32,
    pub on_warning_container: u32,
}

const SURFACE_CHROMA: f64 = 3.0;
const PAPER_CHROMA: f64 = 1.5;
const TINT_DIVISOR: f64 = 6.0;
const MAX_TINT_CHROMA: f64 = 10.0;
const PRIMARY_CONTAINER_CHROMA: f64 = 48.0;
const DARK_ON_SURFACE_TONE: i32 = 97;
const DARK_CARD_TONE: f64 = 15.0;
const DARK_BLOCK_TONE: f64 = 21.0;
const DARK_INSET_TONE: f64 = 27.0;
const MIN_ACCENT_TONE: f64 = 35.0;
const MAX_ACCENT_TONE: f64 = 60.0;
const DARK_ACCENT_LIFT: f64 = 3.0;
const MIN_DARK_ACCENT_TONE: f64 = 50.0;
const MAX_DARK_ACCENT_TONE: f64 = 75.0;

fn argb(rgb: Rgb) -> u32 {
    0xff00_0000 | (rgb.red as u32) << 16 | (rgb.green as u32) << 8 | rgb.blue as u32
}

fn rgb(argb: u32) -> Rgb {
    Rgb { red: (argb >> 16) as u8, green: (argb >> 8) as u8, blue: argb as u8 }
}

pub fn hct_color(hue: f64, chroma: f64, tone: f64) -> u32 {
    hct_argb(Hct::from(hue, chroma, tone))
}

fn hct_argb(hct: Hct) -> u32 {
    argb(Rgb::from(hct))
}

fn tone_of(argb_value: u32) -> f64 {
    Hct::new(rgb(argb_value)).get_tone()
}

fn dynamic_scheme(source: Hct, dark: bool, style: ColorStyle) -> DynamicScheme {
    match style {
        ColorStyle::Vivid | ColorStyle::Tinted => SchemeContent::new(source, dark, Some(0.0)).scheme,
        ColorStyle::Soft => SchemeTonalSpot::new(source, dark, Some(0.0)).scheme,
        ColorStyle::Monochrome => SchemeMonochrome::new(source, dark, Some(0.0)).scheme,
    }
}

fn accent(seed_color: u32, seed: Hct, dark: bool) -> (u32, u32) {
    let tone = if dark {
        (seed.get_tone() + DARK_ACCENT_LIFT).clamp(MIN_DARK_ACCENT_TONE, MAX_DARK_ACCENT_TONE)
    } else {
        seed.get_tone().clamp(MIN_ACCENT_TONE, MAX_ACCENT_TONE)
    };
    let primary = if tone == seed.get_tone() { seed_color | 0xff00_0000 } else { hct_argb(Hct::from(seed.get_hue(), seed.get_chroma(), tone)) };
    let on_primary = if tone <= MAX_ACCENT_TONE {
        0xffff_ffff
    } else {
        argb(TonalPalette::from_hue_and_chroma(seed.get_hue(), seed.get_chroma()).tone(10))
    };
    (primary, on_primary)
}

pub fn seeded_color_scheme(seed_color: u32, dark: bool, style: ColorStyle) -> BoxScheme {
    let source = Hct::new(rgb(seed_color));
    let scheme = dynamic_scheme(source, dark, style);
    let primary_palette = &scheme.primary_palette;
    let container = TonalPalette::from_hue_and_chroma(primary_palette.hue(), primary_palette.chroma().min(PRIMARY_CONTAINER_CHROMA));
    let tint = if style == ColorStyle::Tinted { Some((source.get_chroma() / TINT_DIVISOR).min(MAX_TINT_CHROMA)) } else { None };
    let neutral_palette = &scheme.neutral_palette;
    let neutral_variant_palette = &scheme.neutral_variant_palette;
    // neutral(role, maxChroma, darkTone): the role's palette hue, a capped (or tinted) chroma, the role's tone.
    let neutral = |palette: &TonalPalette, role_argb: u32, max_chroma: f64, dark_tone: Option<f64>| -> u32 {
        let tone = if dark { dark_tone.unwrap_or_else(|| tone_of(role_argb)) } else { tone_of(role_argb) };
        hct_argb(Hct::from(palette.hue(), tint.unwrap_or_else(|| palette.chroma().min(max_chroma)), tone))
    };
    let role = |c: Rgb| argb(c);
    let paper_chroma = if dark { SURFACE_CHROMA } else { PAPER_CHROMA };
    let accent = match style {
        ColorStyle::Vivid | ColorStyle::Tinted => Some(accent(seed_color, source, dark)),
        _ => None,
    };
    let m = |c: material_colors::dynamic_color::DynamicColor| argb(scheme.get_rgb(c));
    let primary = accent.map(|a| a.0).unwrap_or_else(|| m(MaterialDynamicColors::primary()));
    let on_surface = if dark {
        argb(neutral_palette.tone(DARK_ON_SURFACE_TONE))
    } else {
        m(MaterialDynamicColors::on_surface())
    };
    let _ = role;
    let success = TonalPalette::from_hue_and_chroma(Hct::new(rgb(0xFF4CAF50)).get_hue(), Hct::new(rgb(0xFF4CAF50)).get_chroma());
    let warning = TonalPalette::from_hue_and_chroma(Hct::new(rgb(0xFFFF9800)).get_hue(), Hct::new(rgb(0xFFFF9800)).get_chroma());
    let tone = |p: &TonalPalette, light: i32, dark_tone: i32| argb(p.tone(if dark { dark_tone } else { light }));
    BoxScheme {
        dark,
        primary,
        on_primary: accent.map(|a| a.1).unwrap_or_else(|| m(MaterialDynamicColors::on_primary())),
        primary_container: argb(container.tone(if dark { 30 } else { 90 })),
        on_primary_container: argb(container.tone(if dark { 90 } else { 30 })),
        secondary: m(MaterialDynamicColors::secondary()),
        on_secondary: m(MaterialDynamicColors::on_secondary()),
        secondary_container: m(MaterialDynamicColors::secondary_container()),
        on_secondary_container: m(MaterialDynamicColors::on_secondary_container()),
        tertiary: m(MaterialDynamicColors::tertiary()),
        on_tertiary: m(MaterialDynamicColors::on_tertiary()),
        error: m(MaterialDynamicColors::error()),
        on_error: m(MaterialDynamicColors::on_error()),
        error_container: m(MaterialDynamicColors::error_container()),
        on_error_container: m(MaterialDynamicColors::on_error_container()),
        outline: m(MaterialDynamicColors::outline()),
        on_surface,
        on_surface_variant: m(MaterialDynamicColors::on_surface_variant()),
        inverse_surface: m(MaterialDynamicColors::inverse_surface()),
        on_inverse_surface: m(MaterialDynamicColors::inverse_on_surface()),
        inverse_primary: m(MaterialDynamicColors::inverse_primary()),
        scrim: m(MaterialDynamicColors::scrim()),
        surface: neutral(neutral_palette, m(MaterialDynamicColors::surface()), paper_chroma, None),
        surface_bright: neutral(neutral_palette, m(MaterialDynamicColors::surface_bright()), paper_chroma, None),
        surface_dim: neutral(neutral_palette, m(MaterialDynamicColors::surface_dim()), SURFACE_CHROMA, None),
        surface_container_lowest: neutral(neutral_palette, m(MaterialDynamicColors::surface_container_lowest()), SURFACE_CHROMA, None),
        surface_container_low: neutral(neutral_palette, m(MaterialDynamicColors::surface_container_low()), SURFACE_CHROMA, None),
        surface_container: neutral(neutral_palette, m(MaterialDynamicColors::surface_container()), SURFACE_CHROMA, Some(DARK_CARD_TONE)),
        surface_container_high: neutral(neutral_palette, m(MaterialDynamicColors::surface_container_high()), SURFACE_CHROMA, Some(DARK_BLOCK_TONE)),
        surface_container_highest: neutral(neutral_palette, m(MaterialDynamicColors::surface_container_highest()), SURFACE_CHROMA, Some(DARK_INSET_TONE)),
        outline_variant: neutral(neutral_variant_palette, m(MaterialDynamicColors::outline_variant()), 4.0, None),
        success: tone(&success, 40, 80),
        success_container: tone(&success, 90, 30),
        on_success_container: tone(&success, 30, 90),
        warning: tone(&warning, 40, 80),
        warning_container: tone(&warning, 90, 30),
        on_warning_container: tone(&warning, 30, 90),
    }
}

fn shift_tone(color: u32, delta: f64) -> u32 {
    let hct = Hct::new(rgb(color));
    hct_argb(Hct::from(hct.get_hue(), hct.get_chroma(), (hct.get_tone() + delta).clamp(0.0, 100.0)))
}

impl BoxScheme {
    /// toPureBlack: black page surfaces; the containers drop by the surface tone.
    pub fn to_pure_black(mut self, pure_black: bool) -> Self {
        if !pure_black || !self.dark {
            return self;
        }
        let shift = tone_of(self.surface);
        self.surface = 0xff00_0000;
        self.surface_dim = 0xff00_0000;
        self.surface_container_lowest = 0xff00_0000;
        self.surface_container_low = shift_tone(self.surface_container_low, -shift);
        self.surface_container = shift_tone(self.surface_container, -shift);
        self.surface_container_high = shift_tone(self.surface_container_high, -shift);
        self.surface_container_highest = shift_tone(self.surface_container_highest, -shift);
        self.surface_bright = shift_tone(self.surface_bright, -shift);
        self
    }
}

/// Relative luminance in 0..1, for picking a readable mark on a swatch.
pub fn luminance(argb_value: u32) -> f64 {
    let lin = |c: u32| {
        let v = (c & 0xff) as f64 / 255.0;
        if v <= 0.03928 { v / 12.92 } else { ((v + 0.055) / 1.055).powf(2.4) }
    };
    0.2126 * lin(argb_value >> 16) + 0.7152 * lin(argb_value >> 8) + 0.0722 * lin(argb_value)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_blue_keeps_its_accent() {
        let light = seeded_color_scheme(0xff007aff, false, ColorStyle::Vivid);
        assert_eq!(light.primary, 0xff007aff);
        assert_eq!(light.on_primary, 0xffffffff);
        let dark = seeded_color_scheme(0xff007aff, true, ColorStyle::Vivid).to_pure_black(true);
        assert_eq!(dark.surface, 0xff000000);
        assert!(tone_of(dark.on_surface) > 90.0);
    }
}
