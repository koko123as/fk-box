//! Placeholder until the page is ported.

use crate::nav::MenuEntry;
use crate::pages::Mark;
use crate::AppWindow;

pub fn project(_w: &AppWindow, _m: Mark) {}
pub fn opened(_kind: &str, _arg: &str, _id: i32) {}
pub fn closed(_kind: &str, _arg: &str, _id: i32) {}
pub fn act(_id: &str, _arg: &str) -> bool {
    false
}
pub fn act2(_id: &str, _a: &str, _b: &str) -> bool {
    false
}
pub fn menu(_key: &str) -> Vec<MenuEntry> {
    Vec::new()
}
pub fn install(_w: &AppWindow) {}
pub fn open_mitm_certificate() {}
