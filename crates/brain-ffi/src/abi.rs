//! The `#[repr(C)]` mirror of `include/fkbox_brain.h`, with the same layout
//! checks the header makes (compile-time, below) and the conversion of a
//! connection's metadata into the engine's [`Conn`].

use std::ffi::c_void;
use std::mem::{offset_of, size_of};
use std::net::IpAddr;

use fkbox_client_core::rules::engine::{Conn, Network};

pub const FKB_ABI_VERSION: u32 = 1;
pub const FKB_DOMAIN_MAX: usize = 256;
pub const FKB_NAME_MAX: usize = 128;
pub const FKB_RESOLVE_MAX: usize = 16;

pub const FKB_OK: i32 = 0;
pub const FKB_E_INVALID: i32 = -1;
pub const FKB_E_NOT_READY: i32 = -2;
pub const FKB_E_PANIC: i32 = -3;
pub const FKB_E_UNIMPLEMENTED: i32 = -4;
pub const FKB_E_TIMEOUT: i32 = -5;
pub const FKB_E_BUFFER: i32 = -6;
pub const FKB_E_HOST: i32 = -7;
pub const FKB_E_NOT_FOUND: i32 = -8;

pub const FKB_NET_UNKNOWN: u8 = 0;
pub const FKB_NET_TCP: u8 = 1;
pub const FKB_NET_UDP: u8 = 2;

pub const FKB_IP_NONE: u8 = 0;
pub const FKB_IP_V4: u8 = 4;
pub const FKB_IP_V6: u8 = 6;

/// `FKB_PROTO_*` by value: the sniffer names the rules compare.
pub const PROTOCOLS: [&str; 11] = ["", "http", "tls", "quic", "dns", "bittorrent", "stun", "dtls", "ssh", "rdp", "ntp"];

pub const FKB_DOMAIN_NONE: u8 = 0;
pub const FKB_DOMAIN_FQDN: u8 = 1;
pub const FKB_DOMAIN_SNIFFED: u8 = 2;

pub const FKB_UID_UNKNOWN: i32 = -1;
pub const FKB_INBOUND_UNKNOWN: u32 = u32::MAX;

pub const FKB_ACTION_ROUTE: u16 = 0;
pub const FKB_ACTION_REJECT: u16 = 1;
pub const FKB_ACTION_REJECT_DROP: u16 = 2;
pub const FKB_ACTION_HIJACK_DNS: u16 = 3;

pub const FKB_DECISION_DNS_MISS: u16 = 0x0001;
pub const FKB_RULE_FINAL: u32 = u32::MAX;

pub const FKB_STRUCT_CONN_META: u32 = 1;
pub const FKB_STRUCT_DECISION: u32 = 2;
pub const FKB_STRUCT_STR: u32 = 3;
pub const FKB_STRUCT_BUF: u32 = 4;
pub const FKB_STRUCT_RESOLVE_QUERY: u32 = 5;
pub const FKB_STRUCT_RESOLVE_RESULT: u32 = 6;
pub const FKB_STRUCT_PROBE_RESULT: u32 = 7;
pub const FKB_STRUCT_STATS: u32 = 8;
pub const FKB_STRUCT_BRAIN_API: u32 = 9;
pub const FKB_STRUCT_HOST_API: u32 = 10;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct FkbConnMeta {
    pub abi_version: u32,
    pub size: u32,
    pub network: u8,
    pub dst_ip_version: u8,
    pub src_ip_version: u8,
    pub protocol: u8,
    pub src_port: u16,
    pub dst_port: u16,
    pub src_ip: [u8; 16],
    pub dst_ip: [u8; 16],
    pub uid: i32,
    pub inbound_index: u32,
    pub domain_len: u16,
    pub domain_source: u8,
    pub flags: u8,
    pub package_len: u8,
    pub process_len: u8,
    pub reserved: u16,
    pub domain: [u8; FKB_DOMAIN_MAX],
    pub package_name: [u8; FKB_NAME_MAX],
    pub process_name: [u8; FKB_NAME_MAX],
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct FkbDecision {
    pub action: u16,
    pub flags: u16,
    pub outbound: u32,
    pub rule_index: u32,
    pub generation: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct FkbStr {
    pub ptr: *const u8,
    pub len: usize,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct FkbBuf {
    pub ptr: *mut u8,
    pub cap: usize,
    pub len: usize,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct FkbResolveQuery {
    pub abi_version: u32,
    pub size: u32,
    pub qtype: u16,
    pub domain_len: u16,
    pub timeout_ms: u32,
    pub domain: [u8; FKB_DOMAIN_MAX],
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct FkbResolveResult {
    pub count: u32,
    pub ttl_secs: u32,
    pub rcode: i32,
    pub reserved: u32,
    pub family: [u8; FKB_RESOLVE_MAX],
    pub addrs: [[u8; 16]; FKB_RESOLVE_MAX],
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct FkbProbeResult {
    pub http_status: i32,
    pub latency_ms: u32,
    pub flags: u32,
    pub reserved: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct FkbStats {
    pub uplink_bytes: u64,
    pub downlink_bytes: u64,
    pub uplink_rate: u64,
    pub downlink_rate: u64,
    pub connections_tcp: u32,
    pub connections_udp: u32,
    pub memory_inuse: u64,
    pub goroutines: u32,
    pub reserved: u32,
}

pub type FkbDecideFn = unsafe extern "C" fn(meta: *const FkbConnMeta, out: *mut FkbDecision) -> i32;
pub type FkbResolveFn = unsafe extern "C" fn(query: *const FkbResolveQuery, out: *mut FkbResolveResult) -> i32;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct FkbBrainApi {
    pub abi_version: u32,
    pub size: u32,
    pub decide: Option<FkbDecideFn>,
    pub resolve: Option<FkbResolveFn>,
}

pub type FkbStartFn = unsafe extern "C" fn(ctx: *mut c_void, config_json: FkbStr, generation: u32, err: *mut FkbBuf) -> i32;
pub type FkbStopFn = unsafe extern "C" fn(ctx: *mut c_void, err: *mut FkbBuf) -> i32;
pub type FkbProbeFn = unsafe extern "C" fn(
    ctx: *mut c_void,
    outbound_tag: FkbStr,
    url: FkbStr,
    timeout_ms: u32,
    out: *mut FkbProbeResult,
    err: *mut FkbBuf,
) -> i32;
pub type FkbExchangeFn = unsafe extern "C" fn(
    ctx: *mut c_void,
    outbound_tag: FkbStr,
    upstream: FkbStr,
    message: FkbStr,
    timeout_ms: u32,
    response: *mut FkbBuf,
    err: *mut FkbBuf,
) -> i32;
pub type FkbStatsFn = unsafe extern "C" fn(ctx: *mut c_void, out: *mut FkbStats) -> i32;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct FkbHostApi {
    pub abi_version: u32,
    pub size: u32,
    pub ctx: *mut c_void,
    pub start: Option<FkbStartFn>,
    pub stop: Option<FkbStopFn>,
    pub reload: Option<FkbStartFn>,
    pub probe: Option<FkbProbeFn>,
    pub exchange: Option<FkbExchangeFn>,
    pub stats: Option<FkbStatsFn>,
}

// The same numbers as the header's _Static_asserts.
const _: () = {
    assert!(size_of::<usize>() == 8);
    assert!(size_of::<FkbConnMeta>() == 576);
    assert!(offset_of!(FkbConnMeta, network) == 8);
    assert!(offset_of!(FkbConnMeta, src_port) == 12);
    assert!(offset_of!(FkbConnMeta, dst_port) == 14);
    assert!(offset_of!(FkbConnMeta, src_ip) == 16);
    assert!(offset_of!(FkbConnMeta, dst_ip) == 32);
    assert!(offset_of!(FkbConnMeta, uid) == 48);
    assert!(offset_of!(FkbConnMeta, inbound_index) == 52);
    assert!(offset_of!(FkbConnMeta, domain_len) == 56);
    assert!(offset_of!(FkbConnMeta, domain_source) == 58);
    assert!(offset_of!(FkbConnMeta, package_len) == 60);
    assert!(offset_of!(FkbConnMeta, domain) == 64);
    assert!(offset_of!(FkbConnMeta, package_name) == 320);
    assert!(offset_of!(FkbConnMeta, process_name) == 448);
    assert!(size_of::<FkbDecision>() == 16);
    assert!(offset_of!(FkbDecision, outbound) == 4);
    assert!(offset_of!(FkbDecision, rule_index) == 8);
    assert!(offset_of!(FkbDecision, generation) == 12);
    assert!(size_of::<FkbStr>() == 16);
    assert!(size_of::<FkbBuf>() == 24);
    assert!(size_of::<FkbResolveQuery>() == 272);
    assert!(offset_of!(FkbResolveQuery, domain) == 16);
    assert!(size_of::<FkbResolveResult>() == 288);
    assert!(offset_of!(FkbResolveResult, addrs) == 32);
    assert!(size_of::<FkbProbeResult>() == 16);
    assert!(size_of::<FkbStats>() == 56);
    assert!(offset_of!(FkbStats, memory_inuse) == 40);
    assert!(size_of::<FkbBrainApi>() == 24);
    assert!(size_of::<FkbHostApi>() == 64);
    assert!(offset_of!(FkbHostApi, start) == 16);
    assert!(offset_of!(FkbHostApi, stats) == 56);
};

pub fn struct_size(which: u32) -> u32 {
    (match which {
        FKB_STRUCT_CONN_META => size_of::<FkbConnMeta>(),
        FKB_STRUCT_DECISION => size_of::<FkbDecision>(),
        FKB_STRUCT_STR => size_of::<FkbStr>(),
        FKB_STRUCT_BUF => size_of::<FkbBuf>(),
        FKB_STRUCT_RESOLVE_QUERY => size_of::<FkbResolveQuery>(),
        FKB_STRUCT_RESOLVE_RESULT => size_of::<FkbResolveResult>(),
        FKB_STRUCT_PROBE_RESULT => size_of::<FkbProbeResult>(),
        FKB_STRUCT_STATS => size_of::<FkbStats>(),
        FKB_STRUCT_BRAIN_API => size_of::<FkbBrainApi>(),
        FKB_STRUCT_HOST_API => size_of::<FkbHostApi>(),
        _ => 0,
    }) as u32
}

impl FkbStr {
    pub fn of(s: &str) -> FkbStr {
        FkbStr { ptr: s.as_ptr(), len: s.len() }
    }

    pub fn of_bytes(b: &[u8]) -> FkbStr {
        FkbStr { ptr: b.as_ptr(), len: b.len() }
    }

    /// # Safety
    /// `ptr` must point to `len` readable bytes for the returned lifetime.
    pub unsafe fn as_bytes<'a>(&self) -> &'a [u8] {
        if self.ptr.is_null() || self.len == 0 {
            return &[];
        }
        unsafe { std::slice::from_raw_parts(self.ptr, self.len) }
    }
}

impl FkbConnMeta {
    /// An empty record with the header fields set.
    pub fn new() -> FkbConnMeta {
        FkbConnMeta {
            abi_version: FKB_ABI_VERSION,
            size: size_of::<FkbConnMeta>() as u32,
            network: FKB_NET_UNKNOWN,
            dst_ip_version: FKB_IP_NONE,
            src_ip_version: FKB_IP_NONE,
            protocol: 0,
            src_port: 0,
            dst_port: 0,
            src_ip: [0; 16],
            dst_ip: [0; 16],
            uid: FKB_UID_UNKNOWN,
            inbound_index: FKB_INBOUND_UNKNOWN,
            domain_len: 0,
            domain_source: FKB_DOMAIN_NONE,
            flags: 0,
            package_len: 0,
            process_len: 0,
            reserved: 0,
            domain: [0; FKB_DOMAIN_MAX],
            package_name: [0; FKB_NAME_MAX],
            process_name: [0; FKB_NAME_MAX],
        }
    }

    pub fn set_dst(&mut self, ip: IpAddr) {
        self.dst_ip_version = put_ip(&mut self.dst_ip, ip);
    }

    pub fn set_src(&mut self, ip: IpAddr) {
        self.src_ip_version = put_ip(&mut self.src_ip, ip);
    }

    /// Copies `domain` (at most [`FKB_DOMAIN_MAX`] bytes; a longer name is not a valid domain and is left out).
    pub fn set_domain(&mut self, domain: &str, source: u8) {
        if domain.len() > FKB_DOMAIN_MAX {
            return;
        }
        self.domain[..domain.len()].copy_from_slice(domain.as_bytes());
        self.domain_len = domain.len() as u16;
        self.domain_source = source;
    }

    pub fn set_package(&mut self, name: &str) {
        let n = truncate_utf8(name, FKB_NAME_MAX);
        self.package_name[..n].copy_from_slice(&name.as_bytes()[..n]);
        self.package_len = n as u8;
        if n < name.len() {
            self.flags |= 0x01;
        }
    }

    pub fn set_process(&mut self, name: &str) {
        let n = truncate_utf8(name, FKB_NAME_MAX);
        self.process_name[..n].copy_from_slice(&name.as_bytes()[..n]);
        self.process_len = n as u8;
        if n < name.len() {
            self.flags |= 0x02;
        }
    }
}

impl Default for FkbConnMeta {
    fn default() -> FkbConnMeta {
        FkbConnMeta::new()
    }
}

fn truncate_utf8(s: &str, max: usize) -> usize {
    if s.len() <= max {
        return s.len();
    }
    let mut n = max;
    while n > 0 && !s.is_char_boundary(n) {
        n -= 1;
    }
    n
}

fn put_ip(dst: &mut [u8; 16], ip: IpAddr) -> u8 {
    *dst = [0; 16];
    match ip {
        IpAddr::V4(v4) => {
            dst[..4].copy_from_slice(&v4.octets());
            FKB_IP_V4
        }
        IpAddr::V6(v6) => {
            *dst = v6.octets();
            FKB_IP_V6
        }
    }
}

fn ip_of(version: u8, bytes: &[u8; 16]) -> Result<Option<IpAddr>, i32> {
    match version {
        FKB_IP_NONE => Ok(None),
        FKB_IP_V4 => Ok(Some(IpAddr::from([bytes[0], bytes[1], bytes[2], bytes[3]]))),
        FKB_IP_V6 => Ok(Some(IpAddr::from(*bytes))),
        _ => Err(FKB_E_INVALID),
    }
}

fn text(bytes: &[u8], len: usize) -> Result<Option<&str>, i32> {
    if len == 0 {
        return Ok(None);
    }
    let bytes = bytes.get(..len).ok_or(FKB_E_INVALID)?;
    std::str::from_utf8(bytes).map(Some).map_err(|_| FKB_E_INVALID)
}

/// The engine's view of a metadata record; `FKB_E_INVALID` for a record
/// this ABI version does not describe.
pub fn conn_of(meta: &FkbConnMeta) -> Result<Conn<'_>, i32> {
    if meta.abi_version != FKB_ABI_VERSION || meta.size as usize != size_of::<FkbConnMeta>() {
        return Err(FKB_E_INVALID);
    }
    let network = match meta.network {
        FKB_NET_UNKNOWN => None,
        FKB_NET_TCP => Some(Network::Tcp),
        FKB_NET_UDP => Some(Network::Udp),
        _ => return Err(FKB_E_INVALID),
    };
    let protocol = match PROTOCOLS.get(meta.protocol as usize) {
        Some(&"") => None,
        Some(p) => Some(*p),
        None => return Err(FKB_E_INVALID),
    };
    Ok(Conn {
        network,
        src: ip_of(meta.src_ip_version, &meta.src_ip)?,
        src_port: meta.src_port,
        dst: ip_of(meta.dst_ip_version, &meta.dst_ip)?,
        dst_port: meta.dst_port,
        domain: text(&meta.domain, meta.domain_len as usize)?,
        protocol,
        uid: (meta.uid >= 0).then_some(meta.uid as u32),
        package: text(&meta.package_name, meta.package_len as usize)?,
        process: text(&meta.process_name, meta.process_len as usize)?,
        inbound: (meta.inbound_index != FKB_INBOUND_UNKNOWN).then_some(meta.inbound_index),
    })
}
