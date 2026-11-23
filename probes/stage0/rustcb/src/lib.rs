//! Stage-0 Rust "brain" stub: a C-ABI staticlib whose decide() is registered
//! into Go at startup as a plain C function pointer. Pure, synchronous, no
//! allocation, no JSON. Layout mirrors ../ffi/fk_ffi.h.

#![deny(unsafe_op_in_unsafe_fn)]

pub const FK_DOMAIN_MAX: usize = 256;
pub const FK_NET_TCP: u8 = 1;
pub const FK_ACTION_ROUTE: i32 = 0;
pub const FK_ACTION_REJECT: i32 = 1;

#[repr(C)]
pub struct FkConnMeta {
    pub network: u8,
    pub ip_version: u8,
    pub src_port: u16,
    pub dst_port: u16,
    pub domain_len: u16,
    pub src_ip: [u8; 16],
    pub dst_ip: [u8; 16],
    pub uid: i32,
    pub inbound_index: u32,
    pub domain: [u8; FK_DOMAIN_MAX],
}

#[repr(C)]
pub struct FkDecision {
    pub action: i32,
    pub outbound: u32,
}

pub type FkDecideFn = unsafe extern "C" fn(*const FkConnMeta, *mut FkDecision) -> i32;
pub type FkRegisterFn = unsafe extern "C" fn(FkDecideFn);

/// Outbound indices in the host's table (probe convention).
const DIRECT: u32 = 0;
const PROXY: u32 = 1;

/// (suffix, outbound) — a small fixed rule table so the timing reflects some
/// real string work, not an empty call.
static SUFFIX_RULES: &[(&[u8], u32)] = &[
    (b"cn", DIRECT),
    (b"baidu.com", DIRECT),
    (b"qq.com", DIRECT),
    (b"taobao.com", DIRECT),
    (b"bilibili.com", DIRECT),
    (b"jd.com", DIRECT),
    (b"163.com", DIRECT),
    (b"weibo.com", DIRECT),
    (b"zhihu.com", DIRECT),
    (b"alipay.com", DIRECT),
    (b"google.com", PROXY),
    (b"googleapis.com", PROXY),
    (b"gstatic.com", PROXY),
    (b"youtube.com", PROXY),
    (b"ytimg.com", PROXY),
    (b"github.com", PROXY),
    (b"githubusercontent.com", PROXY),
    (b"twitter.com", PROXY),
    (b"x.com", PROXY),
    (b"telegram.org", PROXY),
    (b"openai.com", PROXY),
    (b"chatgpt.com", PROXY),
    (b"anthropic.com", PROXY),
    (b"claude.ai", PROXY),
    (b"wikipedia.org", PROXY),
    (b"netflix.com", PROXY),
    (b"spotify.com", PROXY),
    (b"discord.com", PROXY),
    (b"reddit.com", PROXY),
    (b"instagram.com", PROXY),
    (b"facebook.com", PROXY),
    (b"whatsapp.net", PROXY),
];

fn domain_matches(domain: &[u8], suffix: &[u8]) -> bool {
    if domain.len() < suffix.len() {
        return false;
    }
    let start = domain.len() - suffix.len();
    if !domain[start..].eq_ignore_ascii_case(suffix) {
        return false;
    }
    start == 0 || domain[start - 1] == b'.'
}

fn is_private_v4(ip: &[u8; 16]) -> bool {
    matches!(
        (ip[0], ip[1]),
        (10, _) | (127, _) | (192, 168) | (169, 254)
    ) || (ip[0] == 172 && (16..=31).contains(&ip[1]))
}

pub fn decide(meta: &FkConnMeta) -> FkDecision {
    if meta.network == FK_NET_TCP && (meta.dst_port == 22 || meta.dst_port == 25) {
        return FkDecision { action: FK_ACTION_REJECT, outbound: 0 };
    }
    let len = (meta.domain_len as usize).min(FK_DOMAIN_MAX);
    if len > 0 {
        let domain = &meta.domain[..len];
        for (suffix, outbound) in SUFFIX_RULES {
            if domain_matches(domain, suffix) {
                return FkDecision { action: FK_ACTION_ROUTE, outbound: *outbound };
            }
        }
    } else if meta.ip_version == 4 && is_private_v4(&meta.dst_ip) {
        return FkDecision { action: FK_ACTION_ROUTE, outbound: DIRECT };
    }
    FkDecision { action: FK_ACTION_ROUTE, outbound: PROXY }
}

/// # Safety
/// `meta` and `out` must be valid, non-overlapping pointers (Go guarantees
/// this for the duration of the cgo call).
#[no_mangle]
pub unsafe extern "C" fn fkrust_decide(meta: *const FkConnMeta, out: *mut FkDecision) -> i32 {
    if meta.is_null() || out.is_null() {
        return -1;
    }
    let decision = decide(unsafe { &*meta });
    unsafe { out.write(decision) };
    0
}

/// Called once by the host at startup; registers fkrust_decide with Go.
///
/// # Safety
/// `register_decider` must be the Go side's fkgo_register_decider.
#[no_mangle]
pub unsafe extern "C" fn fkrust_init(register_decider: Option<FkRegisterFn>) {
    if let Some(register) = register_decider {
        unsafe { register(fkrust_decide) };
    }
}

#[no_mangle]
pub extern "C" fn fkrust_meta_size() -> u32 {
    core::mem::size_of::<FkConnMeta>() as u32
}

#[no_mangle]
pub extern "C" fn fkrust_decision_size() -> u32 {
    core::mem::size_of::<FkDecision>() as u32
}

#[cfg(test)]
mod tests {
    use super::*;

    fn meta(domain: &str, port: u16) -> FkConnMeta {
        let mut m = FkConnMeta {
            network: FK_NET_TCP,
            ip_version: 0,
            src_port: 40000,
            dst_port: port,
            domain_len: domain.len() as u16,
            src_ip: [0; 16],
            dst_ip: [0; 16],
            uid: -1,
            inbound_index: 0,
            domain: [0; FK_DOMAIN_MAX],
        };
        m.domain[..domain.len()].copy_from_slice(domain.as_bytes());
        m
    }

    #[test]
    fn layout() {
        assert_eq!(fkrust_meta_size(), 304);
        assert_eq!(fkrust_decision_size(), 8);
    }

    #[test]
    fn rules() {
        assert_eq!(decide(&meta("www.Google.com", 443)).outbound, PROXY);
        assert_eq!(decide(&meta("www.baidu.com", 443)).outbound, DIRECT);
        assert_eq!(decide(&meta("notgoogle.com", 443)).outbound, PROXY);
        assert_eq!(decide(&meta("example.cn", 443)).outbound, DIRECT);
        assert_eq!(decide(&meta("a.example", 22)).action, FK_ACTION_REJECT);
    }
}
