//! FK-box platform-neutral client layer.
//!
//! Grouped by domain (phase 4): `config`, `connection`, `controller`,
//! `geodata`, `rules`, `storage`, `subscription`, `update`, `tools`; the
//! modules below are re-exported at the crate root. The host contract, the
//! JSON call surface and the command-session plan live in `fkbox-mobile-ffi`.
//!
//! - [`storage::Store`]: the client store (service settings, profile
//!   metadata, remote control servers, interface preferences), one SQLite
//!   file whose location the platform shell passes in.
//! - [`legacy`]: the one-time import of the Android app's Room databases and
//!   SharedPreferences into the store.
//! - [`subscription`]: provider headers (`subscription-userinfo`,
//!   `profile-update-interval`, `profile-web-page-url`), the metadata they
//!   leave on a profile, and the automatic update policy.
//! - [`profile_array`]: naming and following the profiles of a JSON array of
//!   configs.
//! - [`content`]: the save flow of a profile's configuration (edits and
//!   subscription updates: revision check, atomic write, rollback), with the
//!   cores' own work behind the platform's [`content::Kernel`].
//! - [`routing`]: the per-core routing rule model, its validation and its
//!   stored and wire forms (compiling a set for a core stays in the kernel
//!   layer).
//! - [`rules::engine`]: the brain's rule engine (Clash/mihomo and sing-box
//!   rules imported into one representation, compiled, decided per
//!   connection); not wired into the app yet.
//! - [`geo`]: the geo resource policy (which files a core needs, bundled
//!   copy versus installed file, automatic update schedule, user addresses).
//! - [`service_settings`]: the service settings snapshot the pages show,
//!   its validation and the write, with what the platform does around it.
//! - [`backup`]: `bbox-1` backups (packing, validation, restore with
//!   rollback).
//! - [`webdav`]: the WebDAV backup account and its upload/download flow
//!   (requests and password encryption by the platform).
//! - [`updates`]: the update check (GitHub release metadata, version codes).
//! - [`terminal`]: the terminal (Ghostty) settings.
//! - [`profiles`]: the profile form's save, the import of a split JSON
//!   array as several profiles, and deleting a profile (targeted column
//!   writes; applying to the service stays with the platform).
//! - [`capture`]: the capture (MITM) settings file (validation by the
//!   capture engine through the platform).
//! - [`ip_quality`]: the dashboard's IP quality cache.
//! - [`remote_servers`]: remote control server addresses and the server
//!   form's save.
//! - [`connections`]: the connections page's state, built from the core's
//!   connection events.
//! - [`logs`], [`dashboard`], [`groups`]: log records, the dashboard view and
//!   the proxy group checks.
//!
//! Phases: docs/architecture/phase2.md, phase3.md, phase4.md.

pub mod config;
pub mod connection;
pub mod controller;
pub mod geodata;
pub mod rules;
pub mod storage;
pub mod subscription;
pub mod update;
pub mod tools;

pub use config::profiles;
pub use config::profile_array;
pub use config::content;
pub use config::service_settings;
pub use config::preferences;
pub use config::remote_servers;
pub use connection::connections;
pub use connection::logs;
pub use controller::groups;
pub use controller::dashboard;
pub use controller::ip_quality;
pub use geodata::geo;
pub use rules::routing;
pub use storage::legacy;
pub use storage::backup;
pub use storage::webdav;
pub(crate) use storage::parcel;
pub(crate) use storage::codec;
pub use update::updates;
pub use tools::capture;
pub use tools::terminal;
