//! FK-box user interface: the Slint component tree (`ui/`, compiled by
//! build.rs), its translations (`i18n/`), the Material 3 color schemes and
//! the fonts. It holds no page logic: the presentation controllers that fill
//! the Slint models and handle their callbacks live in the platform shell
//! (`apps/android/native`, libfkui.so), following docs/architecture/phase4.md.

#![cfg_attr(not(target_os = "android"), allow(dead_code))]

slint::include_modules!();

pub mod fonts;
pub mod i18n;
pub mod theme;

/// The icon name table (`ui/icons/icons.slint`); the shell reads each glyph
/// by name from it.
pub const ICONS_SLINT: &str = include_str!("../ui/icons/icons.slint");
