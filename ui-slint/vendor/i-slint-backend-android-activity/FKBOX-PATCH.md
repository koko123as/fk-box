# i-slint-backend-android-activity 1.18.1, patched for FK-box

Unmodified crates.io source of `i-slint-backend-android-activity` 1.18.1
(GPL-3.0-only OR the Slint licenses, see LICENSES/; `Cargo.toml.orig` and the
registry's `.cargo_vcs_info.json`/`Cargo.lock` left out) with the changes
below, used through `[patch.crates-io]` in ui-slint/Cargo.toml. FK-box builds
it with the `aa-06` and `game-activity` features; the NativeActivity paths are
unchanged. Search for "FK-box patch" to find every hunk.

## Why

On a MEIZU 20 Pro (Flyme, Chinese IME) the text fields of the rule editor
took no input, often showed no keyboard, and backspace closed the app:

    java.lang.IndexOutOfBoundsException: replace (4 ... 0) has end before start
      at android.text.SpannableStringBuilder.delete
      at com.google.androidgamesdk.gametextinput.InputConnection.processKeyEvent
      at com.google.androidgamesdk.gametextinput.InputConnection.onKey

GameActivity's GameTextInput keeps the IME's text in a Java `Editable` and
reports it unchanged: Java String indices (UTF-16 code units), the composing
region inside the text, a selection whose start may be after its end. The
upstream GameActivity path of this backend treated those indices as UTF-8
byte offsets in both directions:

- `set_text_input_state` sent Slint's byte offsets and `start = anchor`,
  `end = cursor` as they were. Past the first CJK character the offsets lie
  beyond the Java text, and a selection made backwards arrives reversed;
  GameTextInput's key handling then deletes `(start, end)` without ordering
  it (the crash above).
- `InputEvent::TextEvent` sliced the UTF-8 text at the UTF-16 composing
  region (`&text[..start]`), which panics when the index is not a char
  boundary, and moved positions after the composition by `+ end - start`
  instead of `- (end - start)`.
- Every Slint update was sent back, including the echo of what the IME had
  just reported. GameTextInput's `setState` calls `restartInput()`, which
  makes Chinese IMEs drop the composition in progress (lost or doubled
  characters, backspace acting on stale text).
- The keyboard was requested with `SHOW_IMPLICIT` (and hidden with
  `HIDE_IMPLICIT_ONLY`), which some ROMs ignore, and no EditorInfo was set,
  so multi-line fields were single-line for the IME.
- The Java helper's `SlintInputView` (the NativeActivity editor) was added
  over the whole window as a focusable text editor even under GameActivity,
  where it could take focus and the IME connection away from GameActivity's
  input view.

## Changes

1. `fkbox_ime.rs` (new, std only): conversion between Slint's state (bytes,
   pre-edit apart) and GameTextInput's (UTF-16, composing region inside,
   selection ordered and clamped), char-boundary safe in both directions.
   The fkui crate compiles the same file in its host tests
   (`ui-slint/src/ime_tests.rs`).
2. `androidwindowadapter.rs`, GameActivity `input_method_request`: sends the
   converted state; skips an update equal to the last state GameTextInput
   reported or was sent; on Enable sets the EditorInfo (text, password,
   number, multi-line from the focused TextInput, action Done for single
   line), shows the keyboard explicitly and once more after 250 ms when no
   keyboard inset arrived; Disable hides it explicitly.
3. `androidwindowadapter.rs`, `InputEvent::TextEvent`: converted with
   `fkbox_ime::from_java`; `InputEvent::TextAction` (the keyboard's Done)
   is dispatched as Return so single-line fields get `accepted`.
4. `androidwindowadapter.rs`, `update_window_insets`: remembers whether a
   keyboard inset is present (for the retry in 2).
5. `java/SlintAndroidJavaHelper.java`: `SlintInputView` is not focusable and
   not a text editor until `show_keyboard` (NativeActivity only) asks for it.

6. `androidwindowadapter.rs` / `javahelper.rs` / `java/SlintAndroidJavaHelper.java`:
   before every `set_text_input_state` the selection is also written to
   GameActivity's `EditorInfo.initialSelStart/End`
   (`fkbox_set_initial_selection`, the getter found by reflection).
   GameTextInput's `setState()` calls `restartInput()`, and the IME restarts
   from that EditorInfo, whose selection only followed the IME's own edits:
   after a tap in the middle of the text the IME still placed the cursor at
   the old position (the end), so input and backspace only worked there.
7. `androidwindowadapter.rs` + `fkbox_ime::commit_moved_composition`: a tap
   that moves the cursor while the IME composes commits the composition where
   it was typed and puts the cursor at the tap (Slint keeps the pre-edit
   apart and would carry it along to the tap). Java gets the committed state
   at once; Slint gets it as a `CommitComposition` in a zero-delay timer, and
   requests in between keep the committed state.

The Kotlin host adds a last guard (BoxActivity.dispatchKeyEvent orders the
editable's selection before GameTextInput handles a key, and a remaining
IndexOutOfBoundsException there is logged instead of closing the app).

To update Slint: copy the new crate version over this directory (without
`Cargo.toml.orig`, `.cargo_vcs_info.json`, `Cargo.lock`), keep `fkbox_ime.rs`
and this file, and re-apply the hunks marked "FK-box patch".
