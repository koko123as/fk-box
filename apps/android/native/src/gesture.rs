//! Which axis a touch belongs to, decided before Slint's own gesture handlers
//! see enough of it to take it.
//!
//! The home pages sit in a `SwipeGestureHandler` (ui/mobile/android/home.slint `Pages`). In
//! Slint 1.19 it takes over a touch as soon as the finger has moved 8 px
//! horizontally and more than half as far horizontally as vertically, i.e.
//! anything within about 63 degrees of horizontal, before the page's
//! vertical `Flickable` (a child, so it sees the touch later) can claim it. A
//! slightly diagonal scroll was taken by the pager, which then refused to
//! drag (its own axis ratio failed), so nothing moved: the page "would not
//! scroll". Every touch now goes through [touch] first (the Android backend's
//! touch observer, and the shot harness): once the finger has moved
//! [DECIDE_PX], a touch that is not clearly horizontal ([AXIS_RATIO], the
//! same ratio as `Theme.touch-axis-ratio`) sets `Motion.vertical-lock`, which
//! keeps the pager's handler disabled until the finger lifts.

use std::cell::Cell;

use slint::ComponentHandle;

/// Distance (logical px) after which the axis is decided; below Slint's
/// 8 px threshold, so the decision always comes first.
pub const DECIDE_PX: f32 = 6.0;
/// A horizontal gesture moves this many times further across than down
/// (`Theme.touch-axis-ratio`).
pub const AXIS_RATIO: f32 = 1.5;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Phase {
    Down,
    Move,
    Up,
    Cancel,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Axis {
    Undecided,
    Horizontal,
    Vertical,
}

#[derive(Clone, Copy)]
struct Touch {
    x: f32,
    y: f32,
    axis: Axis,
}

thread_local! {
    static TOUCH: Cell<Option<Touch>> = const { Cell::new(None) };
}

/// Classifies a movement from the press, or `Undecided` while it is short.
pub fn classify(dx: f32, dy: f32) -> Axis {
    if dx.abs().max(dy.abs()) < DECIDE_PX {
        Axis::Undecided
    } else if dx.abs() > AXIS_RATIO * dy.abs() {
        Axis::Horizontal
    } else {
        Axis::Vertical
    }
}

/// Feeds one touch sample (logical coordinates) before it is dispatched.
pub fn touch(phase: Phase, x: f32, y: f32) {
    let lock = match phase {
        Phase::Down => {
            TOUCH.with(|t| t.set(Some(Touch { x, y, axis: Axis::Undecided })));
            false
        }
        Phase::Move => {
            let Some(mut t) = TOUCH.with(|t| t.get()) else { return };
            if t.axis != Axis::Undecided {
                return;
            }
            t.axis = classify(x - t.x, y - t.y);
            TOUCH.with(|c| c.set(Some(t)));
            if t.axis != Axis::Vertical {
                return;
            }
            true
        }
        Phase::Up | Phase::Cancel => {
            TOUCH.with(|t| t.set(None));
            false
        }
    };
    set_lock(lock);
}

fn set_lock(lock: bool) {
    if let Some(w) = crate::app::window() {
        let motion = w.global::<crate::Motion>();
        if motion.get_vertical_lock() != lock {
            motion.set_vertical_lock(lock);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn short_moves_stay_undecided() {
        assert_eq!(classify(4.0, 3.0), Axis::Undecided);
        assert_eq!(classify(-5.9, 0.0), Axis::Undecided);
    }

    #[test]
    fn diagonal_scroll_is_vertical() {
        // Slint's swipe handler would take this one (dx > 8 and dx > dy / 2).
        assert_eq!(classify(9.0, 12.0), Axis::Vertical);
        assert_eq!(classify(-7.0, -6.0), Axis::Vertical);
        assert_eq!(classify(0.0, 6.0), Axis::Vertical);
    }

    #[test]
    fn clear_swipe_is_horizontal() {
        assert_eq!(classify(10.0, 2.0), Axis::Horizontal);
        assert_eq!(classify(-6.0, 3.9), Axis::Horizontal);
    }
}
