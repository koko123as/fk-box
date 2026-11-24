//! FK-box routing brain behind a fixed C ABI (`include/fkbox_brain.h`,
//! contract in docs/architecture/brain-ffi.md).
//!
//! The Go kernel host links the static library and calls
//! [`fkb_brain_attach`] once at startup; from then on its fkbrain plugin
//! outbound calls the returned `decide` for every new connection. Rules come
//! from `fkbox-client-core`'s engine; this crate only adds the C surface,
//! the snapshot that reloads swap atomically, the DNS cache decide reads and
//! the wrappers over the host's callbacks.
//!
//! Why a crate of its own (not a module of `fkbox-mobile-ffi`): that crate is
//! the UI/session layer for the platform shell (JSON calls, Kotlin host); this
//! one is the kernel's hot path with a C ABI, linked into the Go side, with
//! its own threading and memory rules. Keeping them apart keeps either from
//! pulling the other's dependencies or ABI into its library.

#![deny(unsafe_op_in_unsafe_fn)]

pub mod abi;
pub mod brain;
pub mod dns_cache;
pub mod host;
pub mod profile;

#[cfg(test)]
mod tests;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::OnceLock;

use abi::*;
pub use brain::{Brain, Snapshot};
pub use dns_cache::DnsCache;
pub use host::{HostApi, HostError};

static GLOBAL: OnceLock<Brain> = OnceLock::new();

/// The process's brain (the one the C entry points use).
pub fn global() -> &'static Brain {
    GLOBAL.get_or_init(Brain::new)
}

/// The brain's function table.
pub fn brain_api() -> FkbBrainApi {
    FkbBrainApi {
        abi_version: FKB_ABI_VERSION,
        size: std::mem::size_of::<FkbBrainApi>() as u32,
        decide: Some(fkb_decide),
        resolve: Some(fkb_resolve),
    }
}

/// # Safety
/// `host` is NULL or points to a readable `fkb_host_api`; `brain` points to
/// writable memory for an `fkb_brain_api`.
#[no_mangle]
pub unsafe extern "C" fn fkb_brain_attach(host: *const FkbHostApi, brain: *mut FkbBrainApi) -> i32 {
    if brain.is_null() {
        return FKB_E_INVALID;
    }
    catch_unwind(AssertUnwindSafe(|| {
        if !host.is_null() {
            // SAFETY: checked non-null; the caller guarantees it is readable.
            let raw = unsafe { host.read() };
            match HostApi::from_raw(raw) {
                Ok(h) => global().set_host(h),
                Err(code) => return code,
            }
        }
        // SAFETY: checked non-null; the caller guarantees it is writable.
        unsafe { brain.write(brain_api()) };
        FKB_OK
    }))
    .unwrap_or(FKB_E_PANIC)
}

/// The decide entry point handed to the host through `fkb_brain_api`.
///
/// # Safety
/// `meta` points to a readable `fkb_conn_meta` and `out` to a writable
/// `fkb_decision`, both for the duration of the call.
pub unsafe extern "C" fn fkb_decide(meta: *const FkbConnMeta, out: *mut FkbDecision) -> i32 {
    if meta.is_null() || out.is_null() {
        return FKB_E_INVALID;
    }
    // SAFETY: checked non-null; the host keeps the record alive and unchanged for the call.
    let meta = unsafe { &*meta };
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut decision = FkbDecision::default();
        let code = global().decide_meta(meta, &mut decision);
        (code, decision)
    }));
    match result {
        Ok((FKB_OK, decision)) => {
            // SAFETY: checked non-null; the caller guarantees it is writable.
            unsafe { out.write(decision) };
            FKB_OK
        }
        Ok((code, _)) => code,
        Err(_) => FKB_E_PANIC,
    }
}

/// Declared in ABI 1; the brain's resolver arrives in stage 4.
///
/// # Safety
/// `query` points to a readable `fkb_resolve_query`, `out` to a writable
/// `fkb_resolve_result`.
pub unsafe extern "C" fn fkb_resolve(query: *const FkbResolveQuery, out: *mut FkbResolveResult) -> i32 {
    if query.is_null() || out.is_null() {
        return FKB_E_INVALID;
    }
    // SAFETY: checked non-null.
    let q = unsafe { &*query };
    if q.abi_version != FKB_ABI_VERSION || q.size as usize != std::mem::size_of::<FkbResolveQuery>() {
        return FKB_E_INVALID;
    }
    FKB_E_UNIMPLEMENTED
}

#[no_mangle]
pub extern "C" fn fkb_brain_abi_version() -> u32 {
    FKB_ABI_VERSION
}

#[no_mangle]
pub extern "C" fn fkb_struct_size(which: u32) -> u32 {
    struct_size(which)
}
