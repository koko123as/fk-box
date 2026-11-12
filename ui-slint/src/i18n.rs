//! tr(): the Compose UI's translation (ui/Kit.kt `tr`), keyed by the English
//! source text. The table is the same .po the Slint @tr() strings use.

use std::cell::Cell;
use std::collections::HashMap;
use std::sync::OnceLock;

const ZH_PO: &str = include_str!("../i18n/zh/LC_MESSAGES/fkui.po");

thread_local! {
    static CHINESE: Cell<bool> = const { Cell::new(false) };
}

fn table() -> &'static HashMap<String, String> {
    static TABLE: OnceLock<HashMap<String, String>> = OnceLock::new();
    TABLE.get_or_init(|| parse_po(ZH_PO))
}

fn unquote(line: &str) -> String {
    let inner = line.trim().trim_start_matches('"').trim_end_matches('"');
    let mut out = String::with_capacity(inner.len());
    let mut chars = inner.chars();
    while let Some(c) = chars.next() {
        if c == '\\' {
            match chars.next() {
                Some('n') => out.push('\n'),
                Some('t') => out.push('\t'),
                Some(other) => out.push(other),
                None => {}
            }
        } else {
            out.push(c);
        }
    }
    out
}

/// Single-line msgid/msgstr pairs, as tools/gen_slint_i18n.py writes them.
pub fn parse_po(text: &str) -> HashMap<String, String> {
    let mut map = HashMap::new();
    let mut id: Option<String> = None;
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix("msgid ") {
            id = Some(unquote(rest));
        } else if let Some(rest) = line.strip_prefix("msgstr ") {
            if let Some(key) = id.take() {
                if !key.is_empty() {
                    map.insert(key, unquote(rest));
                }
            }
        }
    }
    map
}

/// Follows the system language: Simplified Chinese for "zh", the English source otherwise.
pub fn set_language(language: &str) {
    let chinese = language.starts_with("zh");
    CHINESE.with(|c| c.set(chinese));
    let _ = slint::select_bundled_translation(if chinese { "zh" } else { "" });
}

pub fn is_chinese() -> bool {
    CHINESE.with(|c| c.get())
}

pub fn tr(value: &str) -> String {
    if is_chinese() {
        if let Some(text) = table().get(value) {
            return text.clone();
        }
    }
    value.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_generated_table() {
        let map = parse_po(ZH_PO);
        assert_eq!(map.get("Dashboard").map(String::as_str), Some("仪表盘"));
        assert_eq!(map.get("Imported %d profiles").map(String::as_str), Some("已导入 %d 个配置"));
        assert!(map.len() > 600);
    }
}
