# i-slint-core 1.19.0, patched for FK-box

Unmodified crates.io source of `i-slint-core` 1.19.0 (GPL-3.0-only OR the
Slint licenses, see LICENSES/; `Cargo.toml.orig`, `.cargo_vcs_info.json` and
`.cargo-ok` left out) with one change, used through `[patch.crates-io]` in
`ui-slint/Cargo.toml`.

## SwipeGestureHandler forwards the press at once

`items/input_items.rs`, `SwipeGestureHandler::input_event_filter_before_children`:
on `Pressed` the handler returns `ForwardAndInterceptGrab` instead of
`DelayForwarding(FORWARD_DELAY)`.

Upstream, the handler held the press back for 100 ms. The home pages put a
`SwipeGestureHandler` (`ui/home.slint`, the pager) over each page's
`Flickable`. On a fast flick the first move locks the pager out
(`src/gesture.rs`, `Motion.vertical-lock` disables the handler) while the
press is still held back; the move then lands outside the delaying item and
the held press is dropped. The Flickable never saw a press, so it never took
the moves and the list stayed still (rows only showed their pressed state).
A slow drag scrolled because its press was released to the Flickable after
the 100 ms, before the moves.

Forwarding at once lets the Flickable see the press at once and take the
flick. The handler keeps seeing every move (an item that answers
`ForwardAndInterceptGrab` is asked again on each move) and still takes a
horizontal drag past its threshold, sending the children an `Exit`.

Covered by the host test `shot::tests::fast_flick_is_not_a_tap`.
