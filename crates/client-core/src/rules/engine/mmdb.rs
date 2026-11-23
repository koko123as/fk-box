//! A MaxMind DB reader for country lookups (`geoip.metadb`, which mihomo's
//! `GEOIP` reads): the binary search tree with 24/28/32-bit records and the
//! data section's map/array/string/integer/pointer types. Database types:
//! `Meta-geoip0` (a code or a list of codes), `sing-geoip` (a code) and
//! anything else read as GeoLite2-Country (`country.iso_code`). Codes are
//! compared in lowercase.

use std::net::IpAddr;
use std::sync::Arc;

const METADATA_MARKER: &[u8] = b"\xab\xcd\xefMaxMind.com";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Layout {
    MetaV0,
    Sing,
    Maxmind,
}

pub struct Mmdb {
    data: Arc<[u8]>,
    node_count: u32,
    record_size: u16,
    ip_version: u16,
    tree_size: usize,
    data_start: usize,
    ipv4_start: u32,
    layout: Layout,
    pub database_type: String,
}

/// A decoded data-section value (only the shapes country lookups need).
#[derive(Debug, Clone, PartialEq)]
enum Value {
    Str(String),
    Uint(u128),
    Map(Vec<(String, Value)>),
    Array(Vec<Value>),
    Other,
}

struct Decoder<'a> {
    buf: &'a [u8],
    /// Where pointers are relative to (the data section, or the metadata).
    base: usize,
}

impl<'a> Decoder<'a> {
    fn bytes(&self, at: usize, n: usize) -> Result<&'a [u8], String> {
        self.buf.get(at..at.checked_add(n).ok_or("mmdb: overflow")?).ok_or_else(|| "mmdb: truncated data".to_string())
    }

    fn uint(&self, at: usize, n: usize) -> Result<u128, String> {
        if n > 16 {
            return Err("mmdb: integer too long".into());
        }
        Ok(self.bytes(at, n)?.iter().fold(0u128, |acc, &b| (acc << 8) | u128::from(b)))
    }

    /// (type, size, offset after the control bytes); pointers are resolved by the caller.
    fn control(&self, at: usize) -> Result<(u8, usize, usize), String> {
        let ctrl = *self.buf.get(at).ok_or("mmdb: truncated data")?;
        let mut pos = at + 1;
        let mut kind = ctrl >> 5;
        if kind == 1 {
            return Ok((1, usize::from(ctrl), pos));
        }
        if kind == 0 {
            kind = 7u8.checked_add(*self.buf.get(pos).ok_or("mmdb: truncated data")?).ok_or("mmdb: bad type")?;
            pos += 1;
        }
        let mut size = usize::from(ctrl & 0x1f);
        match size {
            29 => {
                size = 29 + self.uint(pos, 1)? as usize;
                pos += 1;
            }
            30 => {
                size = 285 + self.uint(pos, 2)? as usize;
                pos += 2;
            }
            31 => {
                size = 65821 + self.uint(pos, 3)? as usize;
                pos += 3;
            }
            _ => {}
        }
        Ok((kind, size, pos))
    }

    fn pointer(&self, ctrl: usize, pos: usize) -> Result<(usize, usize), String> {
        let ss = (ctrl >> 3) & 3;
        let vvv = ctrl & 7;
        let (target, len) = match ss {
            0 => ((vvv << 8) | self.uint(pos, 1)? as usize, 1),
            1 => (((vvv << 16) | self.uint(pos, 2)? as usize) + 2048, 2),
            2 => (((vvv << 24) | self.uint(pos, 3)? as usize) + 526_336, 3),
            _ => (self.uint(pos, 4)? as usize, 4),
        };
        Ok((self.base + target, pos + len))
    }

    /// The value at `at` and the offset after it.
    fn decode(&self, at: usize, depth: u32) -> Result<(Value, usize), String> {
        if depth > 32 {
            return Err("mmdb: data nested too deep".into());
        }
        let (kind, size, pos) = self.control(at)?;
        match kind {
            1 => {
                let (target, next) = self.pointer(size, pos)?;
                let (value, _) = self.decode(target, depth + 1)?;
                Ok((value, next))
            }
            2 => {
                let s = std::str::from_utf8(self.bytes(pos, size)?).map_err(|_| "mmdb: invalid UTF-8")?;
                Ok((Value::Str(s.to_string()), pos + size))
            }
            3 => Ok((Value::Other, pos + 8)),
            4 => Ok((Value::Other, pos + size)),
            5 | 6 | 9 | 10 => Ok((Value::Uint(self.uint(pos, size)?), pos + size)),
            8 => Ok((Value::Other, pos + size)),
            15 => Ok((Value::Other, pos + 4)),
            14 => Ok((Value::Other, pos)),
            7 => {
                let mut out = Vec::with_capacity(size.min(64));
                let mut p = pos;
                for _ in 0..size {
                    let (key, next) = self.decode(p, depth + 1)?;
                    let Value::Str(key) = key else { return Err("mmdb: map key is not a string".into()) };
                    let (value, next) = self.decode(next, depth + 1)?;
                    out.push((key, value));
                    p = next;
                }
                Ok((Value::Map(out), p))
            }
            11 => {
                let mut out = Vec::with_capacity(size.min(64));
                let mut p = pos;
                for _ in 0..size {
                    let (value, next) = self.decode(p, depth + 1)?;
                    out.push(value);
                    p = next;
                }
                Ok((Value::Array(out), p))
            }
            k => Err(format!("mmdb: unsupported data type {k}")),
        }
    }
}

fn get<'v>(map: &'v Value, key: &str) -> Option<&'v Value> {
    match map {
        Value::Map(entries) => entries.iter().find(|(k, _)| k == key).map(|(_, v)| v),
        _ => None,
    }
}

impl Mmdb {
    pub fn parse(data: Arc<[u8]>) -> Result<Mmdb, String> {
        let marker =
            data.windows(METADATA_MARKER.len()).rposition(|w| w == METADATA_MARKER).ok_or("not a MaxMind DB (no metadata marker)")?;
        let meta_start = marker + METADATA_MARKER.len();
        let (meta, _) = Decoder { buf: &data, base: meta_start }.decode(meta_start, 0)?;
        let uint = |key: &str| match get(&meta, key) {
            Some(Value::Uint(v)) => Ok(*v),
            _ => Err(format!("mmdb metadata: missing {key}")),
        };
        let node_count = u32::try_from(uint("node_count")?).map_err(|_| "mmdb: node_count too large")?;
        let record_size = u16::try_from(uint("record_size")?).map_err(|_| "mmdb: bad record_size")?;
        let ip_version = u16::try_from(uint("ip_version")?).map_err(|_| "mmdb: bad ip_version")?;
        if !matches!(record_size, 24 | 28 | 32) {
            return Err(format!("mmdb: unsupported record size {record_size}"));
        }
        let database_type = match get(&meta, "database_type") {
            Some(Value::Str(s)) => s.clone(),
            _ => String::new(),
        };
        let layout = match database_type.as_str() {
            "Meta-geoip0" => Layout::MetaV0,
            "sing-geoip" => Layout::Sing,
            _ => Layout::Maxmind,
        };
        let tree_size = (usize::from(record_size) * 2 / 8) * node_count as usize;
        if tree_size + 16 > marker {
            return Err("mmdb: search tree larger than the file".into());
        }
        let mut db =
            Mmdb { data, node_count, record_size, ip_version, tree_size, data_start: tree_size + 16, ipv4_start: 0, layout, database_type };
        if ip_version == 6 {
            let mut node = 0u32;
            for _ in 0..96 {
                if node >= node_count {
                    break;
                }
                node = db.record(node, 0)?;
            }
            db.ipv4_start = node;
        }
        Ok(db)
    }

    fn record(&self, node: u32, bit: usize) -> Result<u32, String> {
        let bytes = usize::from(self.record_size) * 2 / 8;
        let at = node as usize * bytes;
        let b = self.data.get(at..at + bytes).filter(|_| at + bytes <= self.tree_size).ok_or("mmdb: node outside the tree")?;
        Ok(match (self.record_size, bit) {
            (24, 0) => u32::from_be_bytes([0, b[0], b[1], b[2]]),
            (24, _) => u32::from_be_bytes([0, b[3], b[4], b[5]]),
            (28, 0) => (u32::from(b[3] & 0xf0) << 20) | u32::from_be_bytes([0, b[0], b[1], b[2]]),
            (28, _) => (u32::from(b[3] & 0x0f) << 24) | u32::from_be_bytes([0, b[4], b[5], b[6]]),
            (_, 0) => u32::from_be_bytes([b[0], b[1], b[2], b[3]]),
            (_, _) => u32::from_be_bytes([b[4], b[5], b[6], b[7]]),
        })
    }

    /// The data offset (into the file) of `ip`'s record, or `None`.
    pub fn lookup_offset(&self, ip: IpAddr) -> Result<Option<usize>, String> {
        let ip = super::ip::unmap(ip);
        let (bits, width, mut node) = match ip {
            IpAddr::V4(v4) => {
                let start = if self.ip_version == 6 { self.ipv4_start } else { 0 };
                (u128::from(u32::from(v4)), 32u32, start)
            }
            IpAddr::V6(v6) => {
                if self.ip_version != 6 {
                    return Ok(None);
                }
                (u128::from(v6), 128u32, 0)
            }
        };
        let mut i = 0;
        while i < width && node < self.node_count {
            let bit = ((bits >> (width - 1 - i)) & 1) as usize;
            node = self.record(node, bit)?;
            i += 1;
        }
        if node == self.node_count || node < self.node_count {
            return Ok(None);
        }
        let offset = (node - self.node_count) as usize;
        if offset < 16 {
            return Err("mmdb: bad data pointer".into());
        }
        Ok(Some(self.data_start + offset - 16))
    }

    /// Whether the record at `offset` carries `code` (lowercase).
    pub fn offset_has_code(&self, offset: usize, code: &str) -> bool {
        let decoder = Decoder { buf: &self.data, base: self.data_start };
        let Ok((value, _)) = decoder.decode(offset, 0) else { return false };
        let matches = |v: &Value| matches!(v, Value::Str(s) if s.eq_ignore_ascii_case(code));
        match self.layout {
            Layout::MetaV0 | Layout::Sing => match &value {
                Value::Array(items) => items.iter().any(matches),
                other => matches(other),
            },
            Layout::Maxmind => {
                get(&value, "country").and_then(|c| get(c, "iso_code")).is_some_and(matches)
                    || get(&value, "registered_country").and_then(|c| get(c, "iso_code")).is_some_and(matches)
            }
        }
    }

    /// The codes of `ip` (lowercase; empty when the database has none).
    pub fn codes(&self, ip: IpAddr) -> Vec<String> {
        let Ok(Some(offset)) = self.lookup_offset(ip) else { return Vec::new() };
        let decoder = Decoder { buf: &self.data, base: self.data_start };
        let Ok((value, _)) = decoder.decode(offset, 0) else { return Vec::new() };
        let mut out = Vec::new();
        let mut push = |v: &Value| {
            if let Value::Str(s) = v {
                out.push(s.to_ascii_lowercase());
            }
        };
        match (self.layout, &value) {
            (Layout::Maxmind, v) => {
                if let Some(c) = get(v, "country").and_then(|c| get(c, "iso_code")) {
                    push(c);
                }
            }
            (_, Value::Array(items)) => items.iter().for_each(&mut push),
            (_, v) => push(v),
        }
        out
    }

    pub fn has_code(&self, ip: IpAddr, code: &str) -> bool {
        matches!(self.lookup_offset(ip), Ok(Some(offset)) if self.offset_has_code(offset, code))
    }
}

#[cfg(test)]
pub(crate) mod testdata {
    //! A tiny MaxMind DB writer (24-bit records, IPv6 tree) for tests.

    fn ctrl(out: &mut Vec<u8>, kind: u8, size: usize) {
        assert!(size < 29);
        if kind <= 7 {
            out.push((kind << 5) | size as u8);
        } else {
            out.push(size as u8);
            out.push(kind - 7);
        }
    }

    fn string(out: &mut Vec<u8>, s: &str) {
        ctrl(out, 2, s.len());
        out.extend_from_slice(s.as_bytes());
    }

    fn uint(out: &mut Vec<u8>, kind: u8, v: u32) {
        let bytes: Vec<u8> = v.to_be_bytes().iter().copied().skip_while(|&b| b == 0).collect();
        ctrl(out, kind, bytes.len());
        out.extend_from_slice(&bytes);
    }

    pub enum Data<'a> {
        Code(&'a str),
        Codes(&'a [&'a str]),
        Country(&'a str),
    }

    /// `prefixes`: (IPv6 address bits as u128, prefix length, data).
    pub fn build(database_type: &str, prefixes: &[(u128, u8, Data)]) -> Vec<u8> {
        // Tree nodes: [left, right], each a node id or a marker.
        const EMPTY: i64 = -1;
        let mut nodes: Vec<[i64; 2]> = vec![[EMPTY, EMPTY]];
        let mut leaves: Vec<(usize, usize, usize)> = Vec::new(); // (node, bit, data index)
        for (i, (bits, prefix, _)) in prefixes.iter().enumerate() {
            let mut node = 0usize;
            for depth in 0..*prefix as u32 {
                let bit = ((bits >> (127 - depth)) & 1) as usize;
                if depth + 1 == *prefix as u32 {
                    leaves.push((node, bit, i));
                    break;
                }
                if nodes[node][bit] == EMPTY {
                    nodes.push([EMPTY, EMPTY]);
                    nodes[node][bit] = (nodes.len() - 1) as i64;
                }
                node = nodes[node][bit] as usize;
            }
        }
        let mut data = Vec::new();
        let mut offsets = Vec::new();
        for (_, _, d) in prefixes {
            offsets.push(data.len());
            match d {
                Data::Code(c) => string(&mut data, c),
                Data::Codes(cs) => {
                    ctrl(&mut data, 11, cs.len());
                    for c in cs.iter() {
                        string(&mut data, c);
                    }
                }
                Data::Country(c) => {
                    ctrl(&mut data, 7, 1);
                    string(&mut data, "country");
                    ctrl(&mut data, 7, 1);
                    string(&mut data, "iso_code");
                    string(&mut data, c);
                }
            }
        }
        let node_count = nodes.len() as u32;
        let mut records: Vec<[u32; 2]> =
            nodes.iter().map(|n| [n[0], n[1]].map(|c| if c == EMPTY { node_count } else { c as u32 })).collect();
        for (node, bit, i) in leaves {
            records[node][bit] = node_count + 16 + offsets[i] as u32;
        }
        let mut out = Vec::new();
        for r in &records {
            out.extend_from_slice(&r[0].to_be_bytes()[1..]);
            out.extend_from_slice(&r[1].to_be_bytes()[1..]);
        }
        out.extend_from_slice(&[0u8; 16]);
        out.extend_from_slice(&data);
        out.extend_from_slice(super::METADATA_MARKER);
        ctrl(&mut out, 7, 4);
        string(&mut out, "node_count");
        uint(&mut out, 6, node_count);
        string(&mut out, "record_size");
        uint(&mut out, 5, 24);
        string(&mut out, "ip_version");
        uint(&mut out, 5, 6);
        string(&mut out, "database_type");
        string(&mut out, database_type);
        out
    }

    pub fn v4(a: [u8; 4]) -> u128 {
        (0xffffu128 << 32) | u128::from(u32::from_be_bytes(a))
    }
}

#[cfg(test)]
mod tests {
    use super::testdata::*;
    use super::*;

    fn ip(s: &str) -> IpAddr {
        s.parse().unwrap()
    }

    #[test]
    fn meta_geoip0_codes() {
        // IPv4 lives under ::/96 in an IPv6 tree; v4([..]) above is the
        // mapped form, so add the IPv4-compatible path too.
        let compat = |a: [u8; 4]| u128::from(u32::from_be_bytes(a));
        let file = build(
            "Meta-geoip0",
            &[
                (compat([114, 114, 0, 0]), 96 + 16, Data::Code("cn")),
                (compat([1, 1, 1, 0]), 96 + 24, Data::Codes(&["au", "cloudflare"])),
                (0x2400_3200u128 << 96, 32, Data::Code("CN")),
            ],
        );
        let db = Mmdb::parse(Arc::from(file)).unwrap();
        assert_eq!(db.database_type, "Meta-geoip0");
        assert_eq!(db.codes(ip("114.114.114.114")), ["cn"]);
        assert_eq!(db.codes(ip("::ffff:114.114.1.1")), ["cn"]);
        assert_eq!(db.codes(ip("1.1.1.1")), ["au", "cloudflare"]);
        assert!(db.has_code(ip("1.1.1.1"), "cloudflare"));
        assert!(db.has_code(ip("2400:3200::1"), "cn"));
        assert!(!db.has_code(ip("8.8.8.8"), "cn"));
        assert!(db.codes(ip("8.8.8.8")).is_empty());
        let _ = v4([1, 2, 3, 4]);
    }

    #[test]
    fn maxmind_country_layout() {
        let file = build("GeoLite2-Country", &[(u128::from(u32::from_be_bytes([8, 8, 8, 0])), 96 + 24, Data::Country("US"))]);
        let db = Mmdb::parse(Arc::from(file)).unwrap();
        assert!(db.has_code(ip("8.8.8.8"), "us"));
        assert_eq!(db.codes(ip("8.8.8.8")), ["us"]);
        assert!(Mmdb::parse(Arc::from(b"not a db".to_vec())).is_err());
    }
}
