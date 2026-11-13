//! Appearance (pages/AppearancePage.kt): projects the theme preferences onto
//! the Look global (ui/pages/appearance.slint) and handles its actions; the
//! font family page is Rust-built rows.

use std::cell::RefCell;
use std::rc::Rc;

use serde_json::{json, Value};
use slint::{Color, ComponentHandle, ModelRc, VecModel};

use crate::app::{self, data, long, num, ss, truthy};
use crate::i18n::tr;
use crate::nav::{self, MenuEntry};
use crate::pages::{mark, Mark};
use crate::rows::{self, Page, Row};
use crate::theme::{hct_color, luminance, seeded_color_scheme, ColorStyle};
use crate::{AppWindow, Look, Swatch};

const DEFAULT_ACCENTS: [u32; 8] = [0xff007aff, 0xffcb30e0, 0xffff2d55, 0xffff383c, 0xffff8d28, 0xffffcc00, 0xff34c759, 0xff8e8e93];
const STYLES: [&str; 4] = ["vivid", "tinted", "soft", "monochrome"];
const GRID_HUES: [f64; 12] = [230.0, 262.0, 285.0, 310.0, 340.0, 20.0, 40.0, 60.0, 80.0, 100.0, 120.0, 145.0];
const GRID_TONES: [f64; 9] = [25.0, 33.0, 41.0, 49.0, 57.0, 65.0, 73.0, 81.0, 89.0];

/// The custom colour page's state: the colour, its HSV, and the colour it replaces.
struct Picker {
    color: u32,
    hsv: (f32, f32, f32),
    old: Option<u32>,
    spectrum: Option<(f32, f32, u32)>,
    hex_tick: i32,
}

thread_local! {
    static PICKER: RefCell<Option<Picker>> = const { RefCell::new(None) };
    static GRID: Vec<u32> = grid_colors();
}

fn grid_colors() -> Vec<u32> {
    let mut out: Vec<u32> = (0..GRID_HUES.len()).map(|i| hct_color(0.0, 0.0, 100.0 - i as f64 * 100.0 / (GRID_HUES.len() - 1) as f64)).collect();
    for tone in GRID_TONES {
        for hue in GRID_HUES {
            out.push(hct_color(hue, 200.0, tone));
        }
    }
    out
}

fn color(argb: u32) -> Color {
    Color::from_argb_encoded(argb)
}

fn accent_colors() -> Vec<u32> {
    data(|d| d.preferences.get("accentColors").and_then(Value::as_array).map(|a| a.iter().filter_map(|v| long(v).map(|x| x as u32)).collect()))
        .unwrap_or_else(|| DEFAULT_ACCENTS.to_vec())
}

fn selected_color() -> Option<u32> {
    data(|d| if truthy(&d.pref("systemColor")) { None } else { Some(long(&d.pref("color")).map(|v| v as u32).unwrap_or(0xff007aff)) })
}

fn custom_font_scale() -> bool {
    data(|d| d.preferences.get("customFontScale").and_then(Value::as_bool).unwrap_or(d.preferences.contains_key("fontScale")))
}

fn style_label(name: &str) -> String {
    let mut c = name.chars();
    let cap = c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default();
    tr(&cap)
}

pub fn project(w: &AppWindow) {
    let look = w.global::<Look>();
    let (theme, pure_black, system, style, font, scale) = data(|d| {
        (
            d.pref_str("theme", "system"),
            truthy(&d.pref("pureBlack")),
            d.system_accent,
            d.pref_str("colorStyle", "vivid").to_lowercase(),
            d.preferences.get("fontFamily").and_then(Value::as_str).map(str::to_string),
            d.pref("fontScale").as_f64(),
        )
    });
    look.set_theme(theme.into());
    look.set_pure_black(pure_black);
    let selected = selected_color();
    let colors = accent_colors();
    let mut swatches = vec![Swatch { fill: color(system), selected: selected.is_none(), system: true, light_check: luminance(system) < 0.5, value: "system".into() }];
    for c in &colors {
        swatches.push(Swatch { fill: color(*c), selected: selected == Some(*c), system: false, light_check: luminance(*c) < 0.5, value: ss(c.to_string()) });
    }
    if let Some(m) = rows::update(look.get_swatches(), swatches) {
        look.set_swatches(m);
    }
    look.set_can_add(colors.len() < 64);
    look.set_style_label(style_label(&style).into());
    look.set_font_label(font.unwrap_or_else(|| tr("Default")).into());
    let custom = custom_font_scale();
    look.set_custom_scale(custom);
    let shown = if custom { scale.unwrap_or(1.0) as f32 } else { w.global::<crate::Theme>().get_text_scale() };
    look.set_scale(shown.clamp(0.8, 1.4));
    project_picker(w);
}

fn project_picker(w: &AppWindow) {
    let look = w.global::<Look>();
    let Some((c, hsv, hex_tick, spectrum)) = PICKER.with(|p| p.borrow().as_ref().map(|p| (p.color, p.hsv, p.hex_tick, p.spectrum))) else { return };
    look.set_pick(color(c));
    look.set_hex(format!("{:06X}", c & 0xffffff).into());
    if look.get_hex_tick() != hex_tick {
        look.set_hex_tick(hex_tick);
    }
    look.set_hue(hsv.0);
    look.set_sat(hsv.1);
    look.set_val(hsv.2);
    look.set_pick_dark_text(luminance(c) >= 0.5);
    let style = ColorStyle::of(&data(|d| d.pref_str("colorStyle", "vivid")));
    let pure = data(|d| truthy(&d.pref("pureBlack")));
    let light = seeded_color_scheme(c, false, style).to_pure_black(pure);
    let dark = seeded_color_scheme(c, true, style).to_pure_black(pure);
    look.set_light_primary(color(light.primary));
    look.set_light_container(color(light.primary_container));
    look.set_dark_primary(color(dark.primary));
    look.set_dark_container(color(dark.primary_container));
    if look.get_grid().row_count() == 0 {
        let grid: Vec<Color> = GRID.with(|g| g.iter().map(|c| color(*c)).collect());
        look.set_grid(ModelRc::new(VecModel::from(grid)));
        look.set_grid_columns(GRID_HUES.len() as i32);
        look.set_grid_rows((GRID_TONES.len() + 1) as i32);
    }
    look.set_grid_selected(GRID.with(|g| g.iter().position(|x| (x & 0xffffff) == (c & 0xffffff)).map(|i| i as i32).unwrap_or(-1)));
    // The spectrum's thumb: where it was picked, else from the hue and lightness.
    let (x, y) = match spectrum {
        Some((x, y, picked)) if picked == c => (x, y),
        _ => {
            let (r, g, b) = rgb(c);
            let lightness = (r.max(g).max(b) + r.min(g).min(b)) / 2.0;
            (hsv.0 / 360.0, 1.0 - lightness)
        }
    };
    look.set_spectrum_x(x);
    look.set_spectrum_y(y);
}

use slint::Model;

fn rgb(c: u32) -> (f32, f32, f32) {
    (((c >> 16) & 0xff) as f32 / 255.0, ((c >> 8) & 0xff) as f32 / 255.0, (c & 0xff) as f32 / 255.0)
}

fn from_rgb(r: f32, g: f32, b: f32) -> u32 {
    let q = |v: f32| ((v.clamp(0.0, 1.0) * 255.0).round() as u32) & 0xff;
    0xff00_0000 | (q(r) << 16) | (q(g) << 8) | q(b)
}

/// android.graphics.Color.colorToHSV
fn hsv_of(c: u32) -> (f32, f32, f32) {
    let (r, g, b) = rgb(c);
    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let delta = max - min;
    let h = if delta == 0.0 {
        0.0
    } else if max == r {
        60.0 * (((g - b) / delta) % 6.0)
    } else if max == g {
        60.0 * ((b - r) / delta + 2.0)
    } else {
        60.0 * ((r - g) / delta + 4.0)
    };
    let h = if h < 0.0 { h + 360.0 } else { h };
    (h, if max == 0.0 { 0.0 } else { delta / max }, max)
}

/// android.graphics.Color.HSVToColor
fn color_of_hsv(h: f32, s: f32, v: f32) -> u32 {
    let c = v * s;
    let x = c * (1.0 - ((h / 60.0) % 2.0 - 1.0).abs());
    let m = v - c;
    let (r, g, b) = hue_rgb(h, c, x);
    from_rgb(r + m, g + m, b + m)
}

fn hue_rgb(h: f32, c: f32, x: f32) -> (f32, f32, f32) {
    match h {
        h if h < 60.0 => (c, x, 0.0),
        h if h < 120.0 => (x, c, 0.0),
        h if h < 180.0 => (0.0, c, x),
        h if h < 240.0 => (0.0, x, c),
        h if h < 300.0 => (x, 0.0, c),
        _ => (c, 0.0, x),
    }
}

fn hsl_to_color(h: f32, s: f32, l: f32) -> u32 {
    let c = (1.0 - (2.0 * l - 1.0).abs()) * s;
    let x = c * (1.0 - ((h / 60.0) % 2.0 - 1.0).abs());
    let m = l - c / 2.0;
    let (r, g, b) = hue_rgb(h, c, x);
    from_rgb(r + m, g + m, b + m)
}

pub fn open() {
    nav::push("appearance", "");
}

/// CustomColorPage for `old` (None: a new colour).
fn edit(old: Option<u32>) {
    let initial = old.or_else(selected_color).unwrap_or_else(|| data(|d| d.system_accent));
    PICKER.with(|p| *p.borrow_mut() = Some(Picker { color: initial, hsv: hsv_of(initial), old, spectrum: None, hex_tick: 1 }));
    nav::push("custom-color", "");
}

fn save_custom() {
    let Some((value, old)) = PICKER.with(|p| p.borrow().as_ref().map(|p| (p.color | 0xff00_0000, p.old))) else { return };
    let mut next = accent_colors();
    let index = old.and_then(|o| next.iter().position(|c| *c == o));
    if let Some(i) = index {
        next.remove(i);
    }
    if !next.contains(&value) {
        next.insert(index.unwrap_or(next.len()), value);
    }
    nav::close_kind("custom-color");
    app::set_preferences(json!({ "accentColors": next, "color": value, "systemColor": false }), |_| {});
}

fn set_pick(c: u32, hsv: Option<(f32, f32, f32)>, from_text: bool) {
    PICKER.with(|p| {
        if let Some(p) = p.borrow_mut().as_mut() {
            p.color = c;
            p.hsv = hsv.unwrap_or_else(|| hsv_of(c));
            if !from_text {
                p.hex_tick += 1;
            }
        }
    });
    mark(Mark::THEME);
}

fn open_fonts() {
    let st: Rc<RefCell<(Option<Vec<String>>, String)>> = Rc::new(RefCell::new((None, String::new())));
    // The families /system/etc/fonts.xml names.
    let mut found: Vec<String> = Vec::new();
    match std::fs::read_to_string("/system/etc/fonts.xml") {
        Ok(xml) => {
            for part in xml.split("<family").skip(1) {
                let head = part.split('>').next().unwrap_or("");
                if let Some(rest) = head.split("name=\"").nth(1) {
                    if let Some(name) = rest.split('"').next() {
                        if !found.iter().any(|f| f.eq_ignore_ascii_case(name)) {
                            found.push(name.to_string());
                        }
                    }
                }
            }
        }
        Err(e) => app::report(&e.to_string()),
    }
    found.sort_by_key(|a| a.to_lowercase());
    st.borrow_mut().0 = Some(found);
    let a = st.clone();
    rows::push(move || {
        let s = a.borrow();
        let selected = data(|d| d.preferences.get("fontFamily").and_then(Value::as_str).map(str::to_string));
        let query = s.1.to_lowercase();
        let mut families: Vec<Option<String>> = vec![None];
        families.extend(s.0.clone().unwrap_or_default().into_iter().filter(|f| f.to_lowercase().contains(&query)).map(Some));
        let mut rows = Vec::new();
        for family in families {
            let label = family.clone().unwrap_or_else(|| tr("Default"));
            let f = family.clone();
            let mut r = Row::item(&label).key(family.as_deref().unwrap_or("")).on(move |_| app::set_preference("fontFamily", f.clone().map(Value::String).unwrap_or(Value::Null)));
            if selected == family {
                r = r.trailing("check-circle", || {});
            }
            r.data.position = 4;
            rows.push(r);
            rows.push(Row::spacer(8));
        }
        let b = a.clone();
        Page::new(&tr("Font family")).loading(s.0.is_none()).rows(rows).search(move |q| b.borrow_mut().1 = q)
    });
}

pub fn act(id: &str, arg: &str) -> bool {
    match id {
        "look-theme" => app::set_preference("theme", json!(arg)),
        "look-pure-black" => app::set_preference("pureBlack", json!(arg == "1")),
        "look-select" => {
            if arg == "system" {
                app::set_preferences(json!({ "systemColor": true }), |_| {});
            } else if let Ok(v) = arg.parse::<u32>() {
                app::set_preferences(json!({ "systemColor": false, "color": v }), |_| {});
            }
        }
        "look-reset-colors" => nav::confirm("Reset accent colors?", None, "Reset", |ok| {
            if ok {
                app::set_preferences(json!({ "accentColors": DEFAULT_ACCENTS, "color": 0xff007aff_u32, "systemColor": false, "colorStyle": "vivid" }), |_| {});
            }
        }),
        "look-add" => edit(None),
        "look-style" => {
            let current = data(|d| d.pref_str("colorStyle", "vivid").to_lowercase());
            let labels: Vec<String> = STYLES.iter().map(|s| style_label(s)).collect();
            nav::pick(&tr("Color style"), labels, STYLES.iter().position(|s| *s == current), |picked| {
                if let Some(i) = picked {
                    app::set_preference("colorStyle", json!(STYLES[i]));
                }
            });
        }
        "look-font" => open_fonts(),
        "look-custom-scale" => app::set_preference("customFontScale", json!(arg == "1")),
        "look-scale" => {
            if let Ok(v) = arg.parse::<f64>() {
                app::set_preference("fontScale", json!((v * 20.0).round() / 20.0));
            }
        }
        "look-custom-save" => save_custom(),
        "look-hex" => {
            let filtered: String = arg.chars().filter(|c| c.is_ascii_hexdigit()).take(6).collect();
            if filtered.len() == 6 {
                if let Ok(v) = u32::from_str_radix(&filtered, 16) {
                    set_pick(0xff00_0000 | v, None, true);
                }
            }
        }
        "look-grid" => {
            if let Ok(i) = arg.parse::<f32>() {
                if let Some(c) = GRID.with(|g| g.get(i as usize).copied()) {
                    set_pick(c, None, false);
                }
            }
        }
        _ => return false,
    }
    true
}

pub fn act2(id: &str, a: &str, b: &str) -> bool {
    match id {
        "look-spectrum" => {
            let (x, y) = (a.parse::<f32>().unwrap_or(0.0), b.parse::<f32>().unwrap_or(0.0));
            let c = hsl_to_color((x * 360.0).clamp(0.0, 359.99), 1.0, 1.0 - y);
            PICKER.with(|p| {
                if let Some(p) = p.borrow_mut().as_mut() {
                    p.spectrum = Some((x, y, c));
                }
            });
            set_pick(c, None, false);
        }
        "look-hsv" => {
            let v = b.parse::<f32>().unwrap_or(0.0);
            let hsv = PICKER.with(|p| p.borrow().as_ref().map(|p| p.hsv));
            if let Some((h, s, val)) = hsv {
                let next = match a {
                    "h" => (v, s, val),
                    "s" => (h, v, val),
                    _ => (h, s, v),
                };
                set_pick(color_of_hsv(next.0, next.1, next.2), Some(next), false);
            }
        }
        _ => return false,
    }
    true
}

pub fn menu(key: &str) -> Vec<MenuEntry> {
    let Some(value) = key.strip_prefix("look-swatch:").and_then(|v| v.parse::<u32>().ok()) else { return Vec::new() };
    vec![
        MenuEntry::act("Edit", "edit", move || edit(Some(value))),
        MenuEntry::act("Delete", "delete", move || {
            nav::confirm("Delete this color?", None, "Delete", move |ok| {
                if ok {
                    let colors: Vec<u32> = accent_colors().into_iter().filter(|c| *c != value).collect();
                    let mut values = json!({ "accentColors": colors });
                    if selected_color() == Some(value) {
                        values["systemColor"] = json!(true);
                    }
                    app::set_preferences(values, |_| {});
                }
            });
        })
        .danger(),
    ]
}

pub fn closed(kind: &str) {
    if kind == "custom-color" {
        PICKER.with(|p| *p.borrow_mut() = None);
    }
}

#[allow(dead_code)]
fn unused() -> f64 {
    num(&Value::Null)
}
