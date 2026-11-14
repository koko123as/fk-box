//! Host tests of the input-connection text conversion the vendored Android
//! backend uses (vendor/i-slint-backend-android-activity/fkbox_ime.rs): UTF-8
//! byte offsets against Java's UTF-16 indices with Chinese text and emoji,
//! compositions, backspace and reversed selections.

#[path = "../vendor/i-slint-backend-android-activity/fkbox_ime.rs"]
mod fkbox_ime;

use fkbox_ime::*;

#[test]
fn utf16_and_byte_offsets_round_trip() {
    let text = "a你😀b"; // bytes 0,1,4,8,9  utf16 0,1,2,4,5
    assert_eq!(utf16_len(text), 5);
    for (byte, unit) in [(0, 0), (1, 1), (4, 2), (8, 4), (9, 5)] {
        assert_eq!(byte_to_utf16(text, byte), unit);
        assert_eq!(utf16_to_byte(text, unit), byte);
    }
    // Inside a char: the char's start, never a panic.
    assert_eq!(byte_to_utf16(text, 2), 1);
    assert_eq!(byte_to_utf16(text, 6), 2);
    assert_eq!(utf16_to_byte(text, 3), 4); // between the emoji's surrogates
    // Past the end: clamped.
    assert_eq!(byte_to_utf16(text, 100), 5);
    assert_eq!(utf16_to_byte(text, 100), text.len());
    assert_eq!(floor_char_boundary(text, 3), 1);
}

#[test]
fn java_composition_becomes_slint_preedit_without_panicking() {
    // IME composing "shi" after "你好": Java region (2, 5), cursor 5.
    let e = from_java("你好shi", (5, 5), Some((2, 5)));
    assert_eq!(e.text, "你好");
    assert_eq!(e.preedit, "shi");
    assert_eq!(e.preedit_offset, 6);
    assert_eq!((e.cursor, e.anchor), (6, 6));
    // Upstream sliced the UTF-8 text at index 2 here: inside "你" (panic).
    let e = from_java("你n", (2, 2), Some((1, 2)));
    assert_eq!((e.text.as_str(), e.preedit.as_str(), e.cursor), ("你", "n", 3));
    // Composition in the middle, text after it: positions after the region move back.
    let e = from_java("你ab好", (3, 3), Some((1, 3)));
    assert_eq!((e.text.as_str(), e.preedit.as_str(), e.preedit_offset, e.cursor), ("你好", "ab", 3, 3));
    // A reversed or out-of-range region is ordered and clamped.
    let e = from_java("你好", (2, 2), Some((9, 1)));
    assert_eq!((e.text.as_str(), e.preedit.as_str()), ("你", "好"));
}

#[test]
fn commit_backspace_and_selection_from_java() {
    // Commit "是": no region, cursor after it.
    let e = from_java("你好是", (3, 3), None);
    assert_eq!((e.text.as_str(), e.preedit.as_str(), e.cursor, e.anchor), ("你好是", "", 9, 9));
    // Backspace in the composition: region shrinks to "sh".
    let e = from_java("你好sh", (4, 4), Some((2, 4)));
    assert_eq!((e.text.as_str(), e.preedit.as_str(), e.cursor), ("你好", "sh", 6));
    // Backspace of the last composing letter: region gone, text as before.
    let e = from_java("你好", (2, 2), Some((-1, -1)));
    assert_eq!((e.text.as_str(), e.preedit.as_str(), e.cursor), ("你好", "", 6));
    // Backspace over committed CJK.
    let e = from_java("你", (1, 1), None);
    assert_eq!((e.text.as_str(), e.cursor), ("你", 3));
    // Select all (anchor 0, cursor end) and a reversed selection keep their direction for Slint.
    let e = from_java("😀ab", (0, 4), None);
    assert_eq!((e.anchor, e.cursor), (0, 6));
    let e = from_java("😀ab", (4, 0), None);
    assert_eq!((e.anchor, e.cursor), (6, 0));
    // An unset selection (-1) is the end.
    let e = from_java("ab", (-1, -1), None);
    assert_eq!((e.anchor, e.cursor), (2, 2));
}
