//! Safe wrappers over the host's function table (Rust -> Go calls). Every
//! output goes into a buffer this side allocates and owns; the host only
//! writes into it during the call.

use std::mem::size_of;

use crate::abi::*;

/// The host's table as attached. The function pointers are thread-safe by
/// contract (docs/architecture/brain-ffi.md) and `ctx` is an opaque handle
/// this side never dereferences.
#[derive(Clone, Copy)]
pub struct HostApi(pub(crate) FkbHostApi);

// SAFETY: the host promises its functions may be called from any thread and
// `ctx` is only passed back to them.
unsafe impl Send for HostApi {}
unsafe impl Sync for HostApi {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostError {
    pub code: i32,
    pub message: String,
}

impl std::fmt::Display for HostError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "host error {}: {}", self.code, self.message)
    }
}

impl std::error::Error for HostError {}

const ERR_CAP: usize = 1024;
/// The largest DNS message over TCP.
pub const MAX_DNS_MESSAGE: usize = 65535;

struct ErrBuf {
    bytes: Vec<u8>,
    buf: FkbBuf,
}

impl ErrBuf {
    fn new() -> ErrBuf {
        let mut bytes = vec![0u8; ERR_CAP];
        let buf = FkbBuf { ptr: bytes.as_mut_ptr(), cap: bytes.len(), len: 0 };
        ErrBuf { bytes, buf }
    }

    fn result(&self, code: i32) -> Result<(), HostError> {
        if code == FKB_OK {
            return Ok(());
        }
        let n = self.buf.len.min(self.bytes.len());
        Err(HostError { code, message: String::from_utf8_lossy(&self.bytes[..n]).into_owned() })
    }
}

fn missing(name: &str) -> HostError {
    HostError { code: FKB_E_UNIMPLEMENTED, message: format!("the host does not provide {name}") }
}

impl HostApi {
    /// Checks a table the host passed to `fkb_brain_attach`.
    pub fn from_raw(api: FkbHostApi) -> Result<HostApi, i32> {
        if api.abi_version != FKB_ABI_VERSION || api.size as usize != size_of::<FkbHostApi>() {
            return Err(FKB_E_INVALID);
        }
        Ok(HostApi(api))
    }

    pub fn start(&self, config_json: &str, generation: u32) -> Result<(), HostError> {
        let f = self.0.start.ok_or_else(|| missing("start"))?;
        let mut err = ErrBuf::new();
        // SAFETY: the arguments live for the call; the host writes at most err.cap bytes.
        let code = unsafe { f(self.0.ctx, FkbStr::of(config_json), generation, &mut err.buf) };
        err.result(code)
    }

    pub fn reload(&self, config_json: &str, generation: u32) -> Result<(), HostError> {
        let f = self.0.reload.ok_or_else(|| missing("reload"))?;
        let mut err = ErrBuf::new();
        // SAFETY: as in start.
        let code = unsafe { f(self.0.ctx, FkbStr::of(config_json), generation, &mut err.buf) };
        err.result(code)
    }

    pub fn stop(&self) -> Result<(), HostError> {
        let f = self.0.stop.ok_or_else(|| missing("stop"))?;
        let mut err = ErrBuf::new();
        // SAFETY: as in start.
        let code = unsafe { f(self.0.ctx, &mut err.buf) };
        err.result(code)
    }

    pub fn probe(&self, outbound_tag: &str, url: &str, timeout_ms: u32) -> Result<FkbProbeResult, HostError> {
        let f = self.0.probe.ok_or_else(|| missing("probe"))?;
        let mut err = ErrBuf::new();
        let mut out = FkbProbeResult { http_status: -1, ..FkbProbeResult::default() };
        // SAFETY: as in start; `out` is ours.
        let code = unsafe { f(self.0.ctx, FkbStr::of(outbound_tag), FkbStr::of(url), timeout_ms, &mut out, &mut err.buf) };
        err.result(code).map(|_| out)
    }

    /// One DNS exchange; the reply grows the buffer once if the host asks for more.
    pub fn exchange(&self, outbound_tag: &str, upstream: &str, message: &[u8], timeout_ms: u32) -> Result<Vec<u8>, HostError> {
        let f = self.0.exchange.ok_or_else(|| missing("exchange"))?;
        let mut cap = 4096usize;
        for _ in 0..2 {
            let mut err = ErrBuf::new();
            let mut reply = vec![0u8; cap];
            let mut buf = FkbBuf { ptr: reply.as_mut_ptr(), cap: reply.len(), len: 0 };
            // SAFETY: as in start; the host writes at most buf.cap bytes into `reply`.
            let code = unsafe {
                f(self.0.ctx, FkbStr::of(outbound_tag), FkbStr::of(upstream), FkbStr::of_bytes(message), timeout_ms, &mut buf, &mut err.buf)
            };
            if code == FKB_E_BUFFER && buf.len > cap && buf.len <= MAX_DNS_MESSAGE {
                cap = buf.len;
                continue;
            }
            err.result(code)?;
            reply.truncate(buf.len.min(cap));
            return Ok(reply);
        }
        Err(HostError { code: FKB_E_BUFFER, message: "the host's reply did not fit".into() })
    }

    pub fn stats(&self) -> Result<FkbStats, HostError> {
        let f = self.0.stats.ok_or_else(|| missing("stats"))?;
        let mut out = FkbStats::default();
        // SAFETY: `out` is ours for the call.
        let code = unsafe { f(self.0.ctx, &mut out) };
        if code == FKB_OK {
            Ok(out)
        } else {
            Err(HostError { code, message: "stats failed".into() })
        }
    }
}
