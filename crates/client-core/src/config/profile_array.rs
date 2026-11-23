//! A top-level JSON array of complete configs (v2rayNG's multi custom-config
//! export, or a subscription serving one). The kernel layer splits it (libbox
//! `SplitCoreConfigArray`; an array of Xray configs that differ only in one
//! proxy outbound arrives already merged into one element) and every element
//! becomes its own profile. How the profiles are named, and which element a
//! subscription profile keeps following on later updates, is decided here.

use serde::{Deserialize, Serialize};

/// One element after splitting: `remarks` is "" when the element has none.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Element {
    pub remarks: String,
    pub content: String,
    pub core: String,
}

/// A profile to create: the element's content under its profile name.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Entry {
    pub name: String,
    pub content: String,
    pub core: String,
}

fn source_name(source: &str) -> &str {
    let trimmed = source.trim();
    if trimmed.is_empty() {
        "Profile"
    } else {
        trimmed
    }
}

/// The element's remarks, or "<source> #<n>" (1-based) when it has none.
pub fn name(source: &str, remarks: &str, index: usize) -> String {
    let trimmed = remarks.trim();
    if !trimmed.is_empty() {
        return trimmed.to_string();
    }
    format!("{} #{}", source_name(source), index + 1)
}

/// A lone element without remarks (libbox merged an array of one-node Xray
/// configs into one profile) takes the source's own name; otherwise each
/// element is named by [`name`].
pub fn entries(source: &str, elements: &[Element]) -> Vec<Entry> {
    if let [element] = elements {
        if element.remarks.trim().is_empty() {
            return vec![Entry { name: source_name(source).to_string(), content: element.content.clone(), core: element.core.clone() }];
        }
    }
    elements
        .iter()
        .enumerate()
        .map(|(index, element)| Entry { name: name(source, &element.remarks, index), content: element.content.clone(), core: element.core.clone() })
        .collect()
}

/// The `n` of a profile named by the "<source> #n" fallback: whitespace,
/// `#`, then decimal digits at the end of the (trimmed) name.
fn fallback_number(name: &str) -> Option<usize> {
    let digits_start = name.trim_end_matches(|c: char| c.is_ascii_digit()).len();
    let digits = &name[digits_start..];
    let before = name[..digits_start].strip_suffix('#')?;
    let separator = before.chars().last()?;
    if digits.is_empty() || !matches!(separator, ' ' | '\t' | '\n' | '\u{0B}' | '\u{0C}' | '\r') {
        return None;
    }
    digits.parse::<i32>().ok().and_then(|n| usize::try_from(n).ok())
}

/// Which element a subscription profile keeps following when its URL returns
/// an array: the element whose remarks equal the saved content's remarks,
/// then the profile's name; a profile named by the "<source> #n" fallback
/// follows element n when that element has no remarks; a one-element array
/// always matches. None when nothing matches.
pub fn select(remarks: &[String], saved_remarks: &str, profile_name: &str) -> Option<usize> {
    let trimmed: Vec<&str> = remarks.iter().map(|r| r.trim()).collect();
    let saved = saved_remarks.trim();
    if !saved.is_empty() {
        if let Some(index) = trimmed.iter().position(|r| *r == saved) {
            return Some(index);
        }
    }
    let name = profile_name.trim();
    if !name.is_empty() {
        if let Some(index) = trimmed.iter().position(|r| *r == name) {
            return Some(index);
        }
        if let Some(number) = fallback_number(name) {
            if let Some(index) = number.checked_sub(1) {
                if trimmed.get(index).is_some_and(|r| r.is_empty()) {
                    return Some(index);
                }
            }
        }
    }
    (remarks.len() == 1).then_some(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn element(remarks: &str, content: &str, core: &str) -> Element {
        Element { remarks: remarks.into(), content: content.into(), core: core.into() }
    }

    fn entry(name: &str, content: &str, core: &str) -> Entry {
        Entry { name: name.into(), content: content.into(), core: core.into() }
    }

    #[test]
    fn names_use_remarks_or_the_source_and_position() {
        assert_eq!(name("configs", "  Node A ", 0), "Node A");
        assert_eq!(name("configs", "", 2), "configs #3");
        assert_eq!(name(" configs ", "   ", 1), "configs #2");
        assert_eq!(name("", "", 0), "Profile #1");
    }

    #[test]
    fn entries_keep_content_and_core() {
        assert_eq!(
            entries("list", &[element("a", "{\"x\":1}", "xray"), element("", "{\"y\":2}", "sing-box")]),
            vec![entry("a", "{\"x\":1}", "xray"), entry("list #2", "{\"y\":2}", "sing-box")]
        );
    }

    #[test]
    fn a_lone_element_without_remarks_takes_the_source_name() {
        assert_eq!(entries(" MASQUE-To-Xray-H3 ", &[element("", "{}", "xray")]), vec![entry("MASQUE-To-Xray-H3", "{}", "xray")]);
        assert_eq!(entries("", &[element(" ", "{}", "xray")])[0].name, "Profile");
        assert_eq!(entries("src", &[element("Node", "{}", "xray")])[0].name, "Node");
        assert!(entries("src", &[]).is_empty());
    }

    #[test]
    fn select_follows_remarks_name_then_fallback_index() {
        let remarks: Vec<String> = ["alpha", "", "beta", ""].iter().map(|s| s.to_string()).collect();
        assert_eq!(select(&remarks, "beta", "renamed"), Some(2));
        assert_eq!(select(&remarks, "", " alpha "), Some(0));
        assert_eq!(select(&remarks, "", "sub #2"), Some(1));
        assert_eq!(select(&remarks, "gone", "sub #4"), Some(3));
        assert_eq!(select(&remarks, "", "sub #3"), None);
        assert_eq!(select(&remarks, "", "sub #9"), None);
        assert_eq!(select(&remarks, "", "sub #0"), None);
        assert_eq!(select(&remarks, "", "sub#2"), None);
        assert_eq!(select(&remarks, "", "sub #99999999999"), None);
        assert_eq!(select(&remarks, "missing", "other"), None);
        assert_eq!(select(&["only".to_string()], "changed", "renamed"), Some(0));
    }
}
