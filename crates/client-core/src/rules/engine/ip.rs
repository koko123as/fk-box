//! CIDRs and the compiled IP index: one binary prefix trie per family whose
//! nodes carry the smallest value of the prefixes ending there; a lookup
//! walks the address bits and returns the smallest value on its path.
//!
//! IPv4-mapped IPv6 addresses (`::ffff:a.b.c.d`) and mapped prefixes
//! (`::ffff:0:0/96` and longer) are treated as IPv4, as Go's `netip`
//! `Unmap` does for sing-box and mihomo.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// An address with a prefix length; the address is masked to the prefix.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct IpNet {
    addr: IpAddr,
    prefix: u8,
}

/// The unmapped form of an address.
pub fn unmap(addr: IpAddr) -> IpAddr {
    match addr {
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => IpAddr::V4(v4),
            None => IpAddr::V6(v6),
        },
        v4 => v4,
    }
}

fn mask(bits: u128, width: u8, prefix: u8) -> u128 {
    if prefix == 0 {
        return 0;
    }
    let keep = if prefix as u32 >= 128 { u128::MAX } else { !(u128::MAX >> prefix) };
    let shifted = keep >> (128 - width as u32);
    bits & shifted
}

impl IpNet {
    pub fn new(addr: IpAddr, prefix: u8) -> Result<IpNet, String> {
        let (addr, prefix) = match addr {
            IpAddr::V6(v6) if prefix >= 96 && v6.to_ipv4_mapped().is_some() => {
                (IpAddr::V4(v6.to_ipv4_mapped().unwrap_or(Ipv4Addr::UNSPECIFIED)), prefix - 96)
            }
            other => (other, prefix),
        };
        let width = width_of(&addr);
        if prefix > width {
            return Err(format!("prefix /{prefix} is longer than the address"));
        }
        let bits = mask(addr_bits(&addr), width, prefix);
        Ok(IpNet { addr: from_bits(bits, width), prefix })
    }

    /// `1.2.3.0/24`, `2001:db8::/32`, or a bare address (a host route).
    pub fn parse(text: &str) -> Result<IpNet, String> {
        let text = text.trim();
        let (addr, prefix) = match text.split_once('/') {
            Some((a, p)) => (a, Some(p)),
            None => (text, None),
        };
        let addr: IpAddr = addr.trim().parse().map_err(|_| format!("invalid IP CIDR {text:?}"))?;
        let prefix = match prefix {
            Some(p) => p.trim().parse::<u8>().map_err(|_| format!("invalid IP CIDR {text:?}"))?,
            None => width_of(&addr),
        };
        IpNet::new(addr, prefix).map_err(|e| format!("invalid IP CIDR {text:?}: {e}"))
    }

    pub fn addr(&self) -> IpAddr {
        self.addr
    }

    pub fn prefix(&self) -> u8 {
        self.prefix
    }

    pub fn is_v4(&self) -> bool {
        self.addr.is_ipv4()
    }

    pub fn contains(&self, ip: IpAddr) -> bool {
        let ip = unmap(ip);
        if ip.is_ipv4() != self.addr.is_ipv4() {
            return false;
        }
        let width = width_of(&ip);
        mask(addr_bits(&ip), width, self.prefix) == addr_bits(&self.addr)
    }
}

impl std::fmt::Display for IpNet {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}/{}", self.addr, self.prefix)
    }
}

pub fn width_of(addr: &IpAddr) -> u8 {
    if addr.is_ipv4() {
        32
    } else {
        128
    }
}

pub fn addr_bits(addr: &IpAddr) -> u128 {
    match addr {
        IpAddr::V4(v4) => u128::from(u32::from(*v4)),
        IpAddr::V6(v6) => u128::from(*v6),
    }
}

fn from_bits(bits: u128, width: u8) -> IpAddr {
    if width == 32 {
        IpAddr::V4(Ipv4Addr::from(bits as u32))
    } else {
        IpAddr::V6(Ipv6Addr::from(bits))
    }
}

/// The CIDRs covering an inclusive range of one family (`width` 32 or 128).
pub fn range_to_nets(from: IpAddr, to: IpAddr) -> Result<Vec<IpNet>, String> {
    let (from, to) = (unmap(from), unmap(to));
    if from.is_ipv4() != to.is_ipv4() {
        return Err(format!("range {from}-{to} mixes families"));
    }
    let width = width_of(&from);
    let max: u128 = if width == 32 { u128::from(u32::MAX) } else { u128::MAX };
    let (mut lo, hi) = (addr_bits(&from), addr_bits(&to));
    if lo > hi {
        return Err(format!("range {from}-{to} is reversed"));
    }
    let mut out = Vec::new();
    loop {
        // The largest aligned block starting at lo that stays within hi.
        let mut size = if lo == 0 { width as u32 } else { lo.trailing_zeros().min(width as u32) };
        loop {
            let span = if size >= 128 { u128::MAX } else { (1u128 << size) - 1 };
            if lo.checked_add(span).is_some_and(|end| end <= hi) {
                break;
            }
            size -= 1;
        }
        out.push(IpNet::new(from_bits(lo, width), (width as u32 - size) as u8)?);
        let span = if size >= 128 { u128::MAX } else { (1u128 << size) - 1 };
        let end = lo + span;
        if end >= hi || end == max {
            break;
        }
        lo = end + 1;
    }
    Ok(out)
}

const NONE: u32 = u32::MAX;

#[derive(Clone, Copy)]
struct Node {
    child: [u32; 2],
    value: u32,
}

impl Node {
    const EMPTY: Node = Node { child: [0, 0], value: NONE };
}

/// A binary trie over the bits of one family's addresses.
#[derive(Clone)]
struct Trie {
    nodes: Vec<Node>,
    width: u8,
}

impl Trie {
    fn new(width: u8) -> Trie {
        Trie { nodes: vec![Node::EMPTY], width }
    }

    fn is_empty(&self) -> bool {
        self.nodes.len() == 1 && self.nodes[0].value == NONE
    }

    fn insert(&mut self, bits: u128, prefix: u8, value: u32) {
        let mut node = 0usize;
        for i in 0..prefix as u32 {
            // A shorter prefix with a smaller value already covers this one.
            if self.nodes[node].value <= value {
                return;
            }
            let bit = ((bits >> (self.width as u32 - 1 - i)) & 1) as usize;
            let next = self.nodes[node].child[bit];
            node = if next == 0 {
                self.nodes.push(Node::EMPTY);
                let id = (self.nodes.len() - 1) as u32;
                self.nodes[node].child[bit] = id;
                id as usize
            } else {
                next as usize
            };
        }
        let slot = &mut self.nodes[node].value;
        if value < *slot {
            *slot = value;
        }
    }

    #[inline]
    fn lookup(&self, bits: u128) -> u32 {
        let mut best = NONE;
        let mut node = 0usize;
        let mut i = 0u32;
        loop {
            let n = &self.nodes[node];
            if n.value < best {
                best = n.value;
            }
            if i >= self.width as u32 {
                return best;
            }
            let bit = ((bits >> (self.width as u32 - 1 - i)) & 1) as usize;
            let next = n.child[bit];
            if next == 0 {
                return best;
            }
            node = next as usize;
            i += 1;
        }
    }
}

/// Prefixes with values; [`IpIndex::lookup`] gives the smallest value of the
/// prefixes containing an address.
#[derive(Clone)]
pub struct IpIndex {
    v4: Trie,
    v6: Trie,
}

impl Default for IpIndex {
    fn default() -> IpIndex {
        IpIndex::new()
    }
}

impl IpIndex {
    pub fn new() -> IpIndex {
        IpIndex { v4: Trie::new(32), v6: Trie::new(128) }
    }

    pub fn insert(&mut self, net: IpNet, value: u32) {
        let bits = addr_bits(&net.addr);
        if net.is_v4() {
            self.v4.insert(bits, net.prefix, value);
        } else {
            self.v6.insert(bits, net.prefix, value);
        }
    }

    pub fn is_empty(&self) -> bool {
        self.v4.is_empty() && self.v6.is_empty()
    }

    pub fn node_count(&self) -> usize {
        self.v4.nodes.len() + self.v6.nodes.len()
    }

    #[inline]
    pub fn lookup(&self, ip: IpAddr) -> Option<u32> {
        let ip = unmap(ip);
        let v = match ip {
            IpAddr::V4(_) => self.v4.lookup(addr_bits(&ip)),
            IpAddr::V6(_) => self.v6.lookup(addr_bits(&ip)),
        };
        (v != NONE).then_some(v)
    }

    pub fn contains(&self, ip: IpAddr) -> bool {
        self.lookup(ip).is_some()
    }

    pub fn shrink(&mut self) {
        self.v4.nodes.shrink_to_fit();
        self.v6.nodes.shrink_to_fit();
    }
}

/// Non-global ranges: what `GEOIP,private`/`GEOIP,LAN` (when the geo data has
/// no such code) and sing-box `ip_is_private` match.
pub const PRIVATE_NETS: [&str; 22] = [
    "0.0.0.0/8",
    "10.0.0.0/8",
    "100.64.0.0/10",
    "127.0.0.0/8",
    "169.254.0.0/16",
    "172.16.0.0/12",
    "192.0.0.0/24",
    "192.0.2.0/24",
    "192.88.99.0/24",
    "192.168.0.0/16",
    "198.18.0.0/15",
    "198.51.100.0/24",
    "203.0.113.0/24",
    "224.0.0.0/4",
    "240.0.0.0/4",
    "255.255.255.255/32",
    "::/128",
    "::1/128",
    "fc00::/7",
    "fe80::/10",
    "ff00::/8",
    "2001:db8::/32",
];

pub fn private_index() -> IpIndex {
    let mut index = IpIndex::new();
    for net in PRIVATE_NETS {
        if let Ok(net) = IpNet::parse(net) {
            index.insert(net, 0);
        }
    }
    index
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(s: &str) -> IpAddr {
        s.parse().unwrap()
    }

    #[test]
    fn parses_and_masks() {
        let net = IpNet::parse("10.1.2.3/8").unwrap();
        assert_eq!(net.to_string(), "10.0.0.0/8");
        assert_eq!(IpNet::parse("1.2.3.4").unwrap().prefix(), 32);
        assert_eq!(IpNet::parse("2001:db8::1/32").unwrap().to_string(), "2001:db8::/32");
        assert_eq!(IpNet::parse("::/0").unwrap().prefix(), 0);
        assert_eq!(IpNet::parse("0.0.0.0/0").unwrap().to_string(), "0.0.0.0/0");
        assert!(IpNet::parse("1.2.3.4/33").is_err());
        assert!(IpNet::parse("1.2.3/24").is_err());
        assert!(IpNet::parse("::/129").is_err());
        // A mapped prefix is an IPv4 prefix.
        let mapped = IpNet::parse("::ffff:192.168.0.0/112").unwrap();
        assert!(mapped.is_v4());
        assert_eq!(mapped.to_string(), "192.168.0.0/16");
        assert!(net.contains(ip("10.200.0.1")));
        assert!(net.contains(ip("::ffff:10.9.9.9")));
        assert!(!net.contains(ip("11.0.0.1")));
        assert!(!net.contains(ip("::a00:1")));
    }

    #[test]
    fn index_takes_the_smallest_value_on_the_path() {
        let mut index = IpIndex::new();
        index.insert(IpNet::parse("10.0.0.0/8").unwrap(), 5);
        index.insert(IpNet::parse("10.1.0.0/16").unwrap(), 2);
        index.insert(IpNet::parse("10.1.2.0/24").unwrap(), 9);
        index.insert(IpNet::parse("2001:db8::/32").unwrap(), 1);
        assert_eq!(index.lookup(ip("10.1.2.3")), Some(2));
        assert_eq!(index.lookup(ip("10.2.0.1")), Some(5));
        assert_eq!(index.lookup(ip("::ffff:10.1.9.9")), Some(2));
        assert_eq!(index.lookup(ip("11.0.0.1")), None);
        assert_eq!(index.lookup(ip("2001:db8:1::1")), Some(1));
        assert_eq!(index.lookup(ip("2001:db9::1")), None);
        let mut all = IpIndex::new();
        all.insert(IpNet::parse("0.0.0.0/0").unwrap(), 3);
        assert_eq!(all.lookup(ip("8.8.8.8")), Some(3));
        assert_eq!(all.lookup(ip("::1")), None);
        // A longer prefix under a smaller covering value is not stored.
        let before = index.node_count();
        index.insert(IpNet::parse("10.1.2.128/25").unwrap(), 7);
        assert_eq!(index.node_count(), before);
    }

    #[test]
    fn ranges_become_nets() {
        let nets = range_to_nets(ip("10.0.0.0"), ip("10.0.0.255")).unwrap();
        assert_eq!(nets.iter().map(|n| n.to_string()).collect::<Vec<_>>(), ["10.0.0.0/24"]);
        let nets = range_to_nets(ip("10.0.0.1"), ip("10.0.0.6")).unwrap();
        assert_eq!(nets.iter().map(|n| n.to_string()).collect::<Vec<_>>(), ["10.0.0.1/32", "10.0.0.2/31", "10.0.0.4/31", "10.0.0.6/32"]);
        let nets = range_to_nets(ip("0.0.0.0"), ip("255.255.255.255")).unwrap();
        assert_eq!(nets.iter().map(|n| n.to_string()).collect::<Vec<_>>(), ["0.0.0.0/0"]);
        let nets = range_to_nets(ip("::"), ip("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")).unwrap();
        assert_eq!(nets.iter().map(|n| n.to_string()).collect::<Vec<_>>(), ["::/0"]);
        let nets = range_to_nets(ip("255.255.255.254"), ip("255.255.255.255")).unwrap();
        assert_eq!(nets.iter().map(|n| n.to_string()).collect::<Vec<_>>(), ["255.255.255.254/31"]);
        let nets = range_to_nets(ip("::ffff:1.0.0.0"), ip("::ffff:1.0.0.3")).unwrap();
        assert_eq!(nets.iter().map(|n| n.to_string()).collect::<Vec<_>>(), ["1.0.0.0/30"]);
        assert!(range_to_nets(ip("1.0.0.1"), ip("1.0.0.0")).is_err());
        assert!(range_to_nets(ip("1.0.0.1"), ip("::1")).is_err());
    }

    #[test]
    fn private_ranges() {
        let p = private_index();
        for a in ["10.1.1.1", "192.168.1.1", "127.0.0.1", "::1", "fd00::1", "fe80::1", "::ffff:192.168.0.1"] {
            assert!(p.contains(ip(a)), "{a}");
        }
        for a in ["8.8.8.8", "2400:3200::1", "1.1.1.1"] {
            assert!(!p.contains(ip(a)), "{a}");
        }
    }
}
