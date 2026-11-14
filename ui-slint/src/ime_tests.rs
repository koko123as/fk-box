//! Host tests of the GameTextInput state conversion the vendored Android
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
fn slint_state_goes_to_java_in_utf16_with_an_ordered_selection() {
    // "名称" with the cursor at the end: bytes 6, Java 2.
    let s = to_java("名称", 6, None, "", 0);
    assert_eq!((s.selection_start, s.selection_end, s.compose), (2, 2, None));
    // A backwards selection (anchor after cursor) arrives ordered: GameTextInput
    // deleted (start, end) as given, the crash "replace (4 ... 0)".
    let s = to_java("测试一下", 0, Some(12), "", 0);
    assert_eq!((s.selection_start, s.selection_end), (0, 4));
    // Offsets that are not char boundaries or past the end never panic.
    let s = to_java("测试", 4, Some(99), "", 0);
    assert_eq!((s.selection_start, s.selection_end), (1, 2));
}

#[test]
fn composition_is_inserted_and_marked() {
    // Slint: committed "你好", pre-edit "shi" at the cursor (end).
    let s = to_java("你好", 6, None, "shi", 6);
    assert_eq!(s.text, "你好shi");
    assert_eq!(s.compose, Some((2, 5)));
    assert_eq!((s.selection_start, s.selection_end), (5, 5));
    // In the middle of the text.
    let s = to_java("你好", 3, None, "a", 3);
    assert_eq!(s.text, "你a好");
    assert_eq!(s.compose, Some((1, 2)));
    assert_eq!((s.selection_start, s.selection_end), (2, 2));
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

#[test]
fn an_echo_of_the_ime_state_matches_what_slint_would_send() {
    // The IME reports a state; Slint applies it and sends its own back. The
    // two must compare equal, so no restartInput interrupts the composition.
    for (text, sel, compose) in [
        ("你好shi", (5i64, 5i64), Some((2i64, 5i64))),
        ("你好", (2, 2), None),
        ("a😀b", (4, 1), None),
        ("中文输入法", (5, 5), Some((3, 5))),
        ("", (0, 0), None),
    ] {
        let reported = normalize_java(text, sel, compose);
        let edit = from_java(text, sel, compose);
        let anchor = (edit.anchor != edit.cursor).then_some(edit.anchor);
        // Slint puts the pre-edit at the cursor (InputMethodProperties.preedit_offset).
        let sent = to_java(&edit.text, edit.cursor, anchor, &edit.preedit, edit.cursor);
        assert_eq!(sent, reported, "{text:?} {sel:?} {compose:?}");
    }
}

#[test]
fn a_tap_elsewhere_commits_the_composition_where_it_was_typed() {
    // "example.com" typed, the IME still composing "com" at the end; a tap
    // after "exam" (byte 4 of the text drawn with the pre-edit).
    let last = normalize_java("example.com", (11, 11), Some((8, 11)));
    let e = commit_moved_composition(&last, "example.", 4, None, "com", 4).unwrap();
    assert_eq!((e.text.as_str(), e.preedit.as_str(), e.cursor, e.anchor), ("example.com", "", 4, 4));
    // The cursor still at the composition: typing, nothing to commit.
    assert_eq!(commit_moved_composition(&last, "example.", 8, None, "com", 8), None);
    // No composition: nothing to commit.
    assert_eq!(commit_moved_composition(&last, "example.com", 4, None, "", 4), None);
    // A tap at the end (Slint clamps it to the text without the pre-edit).
    let last = normalize_java("你好shi世界", (5, 5), Some((2, 5)));
    let e = commit_moved_composition(&last, "你好世界", 12, None, "shi", 12).unwrap();
    assert_eq!((e.text.as_str(), e.cursor), ("你好shi世界", "你好shi世界".len()));
    // A tap at the start, with CJK and a selection.
    let e = commit_moved_composition(&last, "你好世界", 0, Some(3), "shi", 0).unwrap();
    assert_eq!((e.cursor, e.anchor), (0, 3));
    // A state from another field (text differs): left alone.
    assert_eq!(commit_moved_composition(&last, "abc", 0, None, "shi", 0), None);
}
