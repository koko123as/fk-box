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
pub mod dns;
pub mod dns_cache;
pub mod host;
pub mod profile;

#[cfg(test)]
mod tests;

use std::net::IpAddr;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Once, OnceLock};
use std::time::{Duration, Instant};

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
        dns_message: Some(fkb_dns_message),
        fakeip_reverse: Some(fkb_fakeip_reverse),
    }
}

static PREFETCHER: Once = Once::new();
/// How often the prefetch thread purges expired answers and saves the fake-ip table.
const HOUSEKEEPING: Duration = Duration::from_secs(30);

/// Starts the global brain's prefetch thread (once): it resolves the names
/// that decide misses queued (`FKB_DECISION_DNS_MISS`), so decide itself
/// never waits for DNS, and does the cache housekeeping.
pub fn start_prefetcher() {
    PREFETCHER.call_once(|| {
        let spawned = std::thread::Builder::new().name("fkb-dns-prefetch".into()).spawn(|| {
            let brain = global();
            let mut last = Instant::now();
            loop {
                let names = brain.dns().wait_prefetch(Duration::from_secs(1));
                if !names.is_empty() {
                    let _ = catch_unwind(AssertUnwindSafe(|| brain.run_prefetch(names)));
                }
                if last.elapsed() >= HOUSEKEEPING {
                    last = Instant::now();
                    brain.dns().purge();
                    brain.dns_state().purge();
                    brain.dns_state().save_fake_ip();
                }
            }
        });
        // Without the thread misses stay misses (the rule is skipped); nothing else depends on it.
        drop(spawned);
    });
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
            start_prefetcher();
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

/// The resolve entry point: real addresses of one name (hosts, cache, then
/// an upstream through the outbound the rules pick), never fake ones.
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
    let result = catch_unwind(AssertUnwindSafe(|| resolve_query(global(), q)));
    match result {
        Ok(Ok(result)) => {
            // SAFETY: checked non-null; the caller guarantees it is writable.
            unsafe { out.write(result) };
            FKB_OK
        }
        Ok(Err(code)) => code,
        Err(_) => FKB_E_PANIC,
    }
}

/// `resolve` for one brain: qtype 1, 28 or 0 (both, A first).
pub fn resolve_query(brain: &Brain, q: &FkbResolveQuery) -> Result<FkbResolveResult, i32> {
    let len = q.domain_len as usize;
    let raw = q.domain.get(..len).filter(|_| len > 0).ok_or(FKB_E_INVALID)?;
    let name = std::str::from_utf8(raw).ok().and_then(fkbox_client_core::rules::engine::domain::normalize).ok_or(FKB_E_INVALID)?;
    let qtypes: &[u16] = match q.qtype {
        0 => &[dns::wire::TYPE_A, dns::wire::TYPE_AAAA],
        1 => &[dns::wire::TYPE_A],
        28 => &[dns::wire::TYPE_AAAA],
        _ => return Err(FKB_E_INVALID),
    };
    let settings = brain.dns_state().settings();
    let ms = if q.timeout_ms == 0 { settings.timeout_ms } else { q.timeout_ms };
    let deadline = Instant::now() + Duration::from_millis(ms as u64);
    let mut result = FkbResolveResult::default();
    let mut ttl = u32::MAX;
    let mut answered = false;
    let mut last = FKB_E_HOST;
    for &qtype in qtypes {
        match brain.resolve_real(&name, qtype, deadline) {
            Ok((rcode, addrs, t)) => {
                if !answered || rcode == 0 {
                    result.rcode = rcode as i32;
                }
                answered = true;
                ttl = ttl.min(t);
                for addr in addrs {
                    let i = result.count as usize;
                    if i >= FKB_RESOLVE_MAX {
                        break;
                    }
                    match addr {
                        IpAddr::V4(v4) => {
                            result.family[i] = 4;
                            result.addrs[i][..4].copy_from_slice(&v4.octets());
                        }
                        IpAddr::V6(v6) => {
                            result.family[i] = 6;
                            result.addrs[i] = v6.octets();
                        }
                    }
                    result.count += 1;
                }
            }
            Err(e) => last = e.code(),
        }
    }
    if !answered {
        return Err(last);
    }
    result.ttl_secs = if ttl == u32::MAX { 0 } else { ttl };
    Ok(result)
}

/// The dns_message entry point: one DNS message answered by the brain.
///
/// # Safety
/// `query` is readable for the call, `reply` points to a writable buffer.
pub unsafe extern "C" fn fkb_dns_message(query: FkbStr, flags: u32, timeout_ms: u32, reply: *mut FkbBuf) -> i32 {
    if reply.is_null() || (query.ptr.is_null() && query.len != 0) {
        return FKB_E_INVALID;
    }
    // SAFETY: the caller keeps `len` bytes at `ptr` alive for the call.
    let bytes = unsafe { query.as_bytes() };
    let result = catch_unwind(AssertUnwindSafe(|| global().dns_message(bytes, flags, timeout_ms)));
    match result {
        // SAFETY: checked non-null; the caller owns the buffer.
        Ok(Ok(message)) => unsafe { write_buf(&mut *reply, &message) },
        Ok(Err(code)) => code,
        Err(_) => FKB_E_PANIC,
    }
}

/// Copies `data` into a caller buffer, `FKB_E_BUFFER` (len = size needed) when it does not fit.
///
/// # Safety
/// `buf.ptr` points to `buf.cap` writable bytes (or is NULL with cap 0).
pub unsafe fn write_buf(buf: &mut FkbBuf, data: &[u8]) -> i32 {
    if data.len() > buf.cap || (buf.ptr.is_null() && !data.is_empty()) {
        buf.len = data.len();
        return FKB_E_BUFFER;
    }
    if !data.is_empty() {
        // SAFETY: fits, per the check above.
        unsafe { std::ptr::copy_nonoverlapping(data.as_ptr(), buf.ptr, data.len()) };
    }
    buf.len = data.len();
    FKB_OK
}

/// The fakeip_reverse entry point (memory only, never blocks).
///
/// # Safety
/// `ip` points to 16 readable bytes, `domain` to a writable buffer.
pub unsafe extern "C" fn fkb_fakeip_reverse(ip: *const u8, ip_version: u8, domain: *mut FkbBuf) -> i32 {
    if ip.is_null() || domain.is_null() {
        return FKB_E_INVALID;
    }
    // SAFETY: checked non-null; the caller passes 16 bytes.
    let bytes = unsafe { std::slice::from_raw_parts(ip, 16) };
    let addr = match ip_version {
        FKB_IP_V4 => IpAddr::from([bytes[0], bytes[1], bytes[2], bytes[3]]),
        FKB_IP_V6 => {
            let mut octets = [0u8; 16];
            octets.copy_from_slice(bytes);
            IpAddr::from(octets)
        }
        _ => return FKB_E_INVALID,
    };
    match catch_unwind(AssertUnwindSafe(|| global().dns_state().reverse(addr))) {
        // SAFETY: checked non-null; the caller owns the buffer.
        Ok(Ok(Some(name))) => unsafe { write_buf(&mut *domain, name.as_bytes()) },
        Ok(Ok(None)) => {
            // SAFETY: as above.
            unsafe { (*domain).len = 0 };
            FKB_OK
        }
        Ok(Err(())) => FKB_E_NOT_FOUND,
        Err(_) => FKB_E_PANIC,
    }
}

#[no_mangle]
pub extern "C" fn fkb_brain_abi_version() -> u32 {
    FKB_ABI_VERSION
}

#[no_mangle]
pub extern "C" fn fkb_struct_size(which: u32) -> u32 {
    struct_size(which)
}
