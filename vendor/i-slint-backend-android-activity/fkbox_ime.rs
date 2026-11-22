#![allow(dead_code)]

// FK-box patch (see FKBOX-PATCH.md): text that GameActivity's input
// connection reports, as an edit for Slint. FK-box edits text in its own
// EditText (ui-slint/src/native_ime.rs) and never configures GameTextInput,
// but the surface view keeps an input connection, so anything an IME commits
// there still arrives and must not panic. Plain std only, so the fkui crate's host
// tests compile this same file (src/ime_tests.rs).
//
// Slint (TextInput, InputMethodProperties, InternalKeyEvent) counts byte
// offsets into UTF-8 text with the pre-edit kept apart from the text.
// GameTextInput hands the Java editable's state through unchanged: Java
// String indices (UTF-16 code units) with the composing region inside the
// text, and a selection that may run backwards (start > end). Upstream 1.18.1
// passed the indices across as they were, so with Chinese text the backend
// sliced UTF-8 at UTF-16 positions (a char boundary panic) and GameTextInput
// got byte offsets past the end of its editable or a reversed selection,
// which its key handling deletes with `delete(4, 0)` (IndexOutOfBounds).

/// An edit for Slint: byte offsets. With a composition, `text` is the
/// committed text without the pre-edit and the positions are in it; the
/// pre-edit sits at `preedit_offset`.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SlintTextEdit {
    pub text: String,
    pub preedit: String,
    pub preedit_offset: usize,
    pub cursor: usize,
    pub anchor: usize,
}

/// The largest char boundary at or below `byte` (clamped to the text).
pub fn floor_char_boundary(text: &str, byte: usize) -> usize {
    let mut b = byte.min(text.len());
    while b > 0 && !text.is_char_boundary(b) {
        b -= 1;
    }
    b
}

pub fn utf16_len(text: &str) -> usize {
    text.chars().map(char::len_utf16).sum()
}

/// A byte offset as a UTF-16 index (an offset inside a char counts as its start).
pub fn byte_to_utf16(text: &str, byte: usize) -> usize {
    let byte = floor_char_boundary(text, byte);
    text[..byte].chars().map(char::len_utf16).sum()
}

/// A UTF-16 index as a byte offset. An index past the end gives the text's
/// length; one between the two halves of a surrogate pair gives the start of
/// that char, so the result is always a char boundary.
pub fn utf16_to_byte(text: &str, index: usize) -> usize {
    let mut units = 0;
    for (byte, c) in text.char_indices() {
        let next = units + c.len_utf16();
        if next > index {
            return byte;
        }
        units = next;
    }
    text.len()
}

/// Java index from GameTextInput (may be -1 for "none" or past the end).
fn java_index(text: &str, index: i64, fallback: usize) -> usize {
    if index < 0 { fallback } else { (index as usize).min(utf16_len(text)) }
}

/// What GameTextInput reported as an edit for Slint. `selection` is
/// (start, end) as Java's Selection gives it: start is the anchor, end the
/// cursor, either order.
pub fn from_java(text: &str, selection: (i64, i64), compose: Option<(i64, i64)>) -> SlintTextEdit {
    let len = utf16_len(text);
    let anchor16 = java_index(text, selection.0, len);
    let cursor16 = java_index(text, selection.1, anchor16);
    let anchor = utf16_to_byte(text, anchor16);
    let cursor = utf16_to_byte(text, cursor16);
    let region = compose
        .filter(|(s, e)| *s >= 0 && *e >= 0)
        .map(|(s, e)| (java_index(text, s, 0), java_index(text, e, 0)))
        .map(|(s, e)| (s.min(e), s.max(e)))
        .filter(|(s, e)| s < e);
    match region {
        Some((s16, e16)) => {
            let (s, e) = (utf16_to_byte(text, s16), utf16_to_byte(text, e16));
            let adjust = |pos: usize| {
                if pos <= s {
                    pos
                } else if pos >= e {
                    pos - (e - s)
                } else {
                    s
                }
            };
            SlintTextEdit {
                text: format!("{}{}", &text[..s], &text[e..]),
                preedit: text[s..e].to_string(),
                preedit_offset: s,
                cursor: adjust(cursor),
                anchor: adjust(anchor),
            }
        }
        None => SlintTextEdit { text: text.to_string(), preedit: String::new(), preedit_offset: 0, cursor, anchor },
    }
}
