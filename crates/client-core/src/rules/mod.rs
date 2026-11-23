//! Routing rules and previews.
//!
//! - [`routing`]: the per-core rule model the settings page edits (stored,
//!   validated, compiled per core in libbox).
//! - [`engine`]: the brain's rule engine (unified representation, Clash and
//!   sing-box importers, compiled matchers, `decide`); stage 1 of the
//!   single-kernel design, not yet used by the app.
pub mod engine;
pub mod routing;
