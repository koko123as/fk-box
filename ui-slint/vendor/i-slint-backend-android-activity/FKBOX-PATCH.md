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

FK-box now edits every text field in its own `android.widget.EditText`
laid over the focused TextInput (`ui-slint/src/native_ime.rs`,
`app/.../bridge/NativeEditor.kt`): the system owns the cursor, selection
handles, context menu and the input connection. The earlier attempts to
make GameTextInput behave (state conversion both ways, echo suppression,
EditorInfo per field, explicit keyboard show and retry,
`fkbox_set_initial_selection`, committing a moved composition) were removed
once that worked; only what still runs is left:

1. `androidwindowadapter.rs`, GameActivity `input_method_request`: empty.
   GameTextInput is never configured and the keyboard is never shown from
   here, so two input connections never fight over it.
2. `fkbox_ime.rs` (new, std only) + `androidwindowadapter.rs`,
   `InputEvent::TextEvent`: GameActivity's surface view still has an input
   connection, so text an IME commits there is converted with
   `fkbox_ime::from_java` (UTF-16 indices to char-boundary byte offsets,
   composing region taken out) instead of panicking. The fkui crate compiles
   the same file in its host tests (`ui-slint/src/ime_tests.rs`).
3. `androidwindowadapter.rs`, `InputEvent::TextAction` (the keyboard's Done)
   is dispatched as Return.
4. `java/SlintAndroidJavaHelper.java`: `SlintInputView` is not focusable and
   not a text editor until `show_keyboard` (NativeActivity only) asks for it,
   so it cannot take focus from the app's EditText.

The Kotlin host adds a last guard (BoxActivity.dispatchKeyEvent orders the
editable's selection before GameTextInput handles a key, and a remaining
IndexOutOfBoundsException there is logged instead of closing the app).

To update Slint: copy the new crate version over this directory (without
`Cargo.toml.orig`, `.cargo_vcs_info.json`, `Cargo.lock`), keep `fkbox_ime.rs`
and this file, and re-apply the hunks marked "FK-box patch".
