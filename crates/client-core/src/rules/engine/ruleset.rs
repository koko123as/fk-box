//! Rule-set file formats.
//!
//! - mihomo rule providers: `yaml` (`payload:` list), `text` (one entry per
//!   line, `#` and `//` comments) and `mrs` (zstd; `MRS\x01`, behavior byte,
//!   count, extra, then the domain succinct set or the IP range list:
//!   `rules/provider/mrs_reader.go`, `component/trie/domain_set_bin.go`,
//!   `component/cidr/ipcidr_set_bin.go`).
//! - sing-box rule sets: `source` (JSON) and `binary` (SRS: `SRS`, version,
//!   zlib; `common/srs/binary.go`). The binary form is turned back into the
//!   JSON headless rules it was compiled from (as `srs.Read` with recovery
//!   does) and goes through the same importer as the source form.
//!
//! Both binary domain sets are LOUDS-encoded succinct tries over reversed
//! keys; [`louds_keys`] enumerates their keys.

use std::io::Read;
use std::net::IpAddr;

use serde_json::{json, Map, Value};

use super::ip::range_to_nets;

/// The keys of a LOUDS trie (bit `i` of the label bitmap: 0 = an edge whose
/// label is the next byte of `labels`, 1 = end of the current node's edges;
/// nodes in BFS order; `leaves` marks nodes that end a key). Keys come back
/// in the orientation they were inserted (both formats reverse the domain
/// rune by rune, which this undoes).
pub(crate) fn louds_keys(leaves: &[u64], bitmap: &[u64], labels: &[u8]) -> Result<Vec<String>, String> {
    let bit = |words: &[u64], i: usize| -> bool { words.get(i >> 6).is_some_and(|w| (w >> (i & 63)) & 1 == 1) };
    let ones: usize = bitmap.iter().map(|w| w.count_ones() as usize).sum();
    let last_one = bitmap.iter().enumerate().rev().find(|(_, w)| **w != 0).map(|(i, w)| i * 64 + 63 - w.leading_zeros() as usize);
    let Some(last_one) = last_one else { return Ok(Vec::new()) };
    let zeros = last_one + 1 - ones;
    if ones != zeros + 1 || labels.len() != zeros {
        return Err("malformed succinct domain set".into());
    }
    // parent[n] and label[n] for every node but the root.
    let mut parent = vec![0u32; ones];
    let mut label = vec![0u8; ones];
    let (mut node, mut next_child, mut next_label) = (0usize, 1usize, 0usize);
    for i in 0..=last_one {
        if bit(bitmap, i) {
            node += 1;
        } else {
            if next_child >= ones {
                return Err("malformed succinct domain set".into());
            }
            parent[next_child] = node as u32;
            label[next_child] = labels[next_label];
            next_child += 1;
            next_label += 1;
        }
    }
    let mut keys = Vec::new();
    let mut path = Vec::new();
    for n in 0..ones {
        if !bit(leaves, n) {
            continue;
        }
        path.clear();
        let mut at = n;
        while at != 0 {
            path.push(label[at]);
            at = parent[at] as usize;
        }
        // `path` is the stored (reversed) key read backwards, i.e. the
        // reversed key's bytes in reverse: un-reverse runes by decoding the
        // stored key and reversing its characters.
        path.reverse();
        let stored = std::str::from_utf8(&path).map_err(|_| "succinct domain set: invalid UTF-8".to_string())?;
        keys.push(stored.chars().rev().collect());
    }
    Ok(keys)
}

struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], String> {
        let end = self.pos.checked_add(n).filter(|&e| e <= self.buf.len()).ok_or("rule set: truncated")?;
        let out = &self.buf[self.pos..end];
        self.pos = end;
        Ok(out)
    }

    fn u8(&mut self) -> Result<u8, String> {
        Ok(self.take(1)?[0])
    }

    fn be_u64(&mut self) -> Result<u64, String> {
        let mut b = [0u8; 8];
        b.copy_from_slice(self.take(8)?);
        Ok(u64::from_be_bytes(b))
    }

    fn be_i64(&mut self) -> Result<i64, String> {
        Ok(self.be_u64()? as i64)
    }

    fn uvarint(&mut self) -> Result<u64, String> {
        let mut out = 0u64;
        for shift in (0..64).step_by(7) {
            let b = self.u8()?;
            out |= u64::from(b & 0x7f) << shift;
            if b < 0x80 {
                return Ok(out);
            }
        }
        Err("rule set: varint too long".into())
    }

    /// A count that must fit in what is left of the input (each item at least `unit` bytes).
    fn count(&mut self, unit: usize) -> Result<usize, String> {
        let n = self.uvarint()? as usize;
        if n.saturating_mul(unit.max(1)) > self.buf.len() - self.pos {
            return Err("rule set: count larger than the data".into());
        }
        Ok(n)
    }

    fn u64_slice(&mut self, n: usize) -> Result<Vec<u64>, String> {
        (0..n).map(|_| self.be_u64()).collect()
    }
}

fn decompress_zlib(data: &[u8]) -> Result<Vec<u8>, String> {
    let mut out = Vec::new();
    flate2::read::ZlibDecoder::new(data).read_to_end(&mut out).map_err(|e| format!("srs: zlib: {e}"))?;
    Ok(out)
}

fn decompress_zstd(data: &[u8]) -> Result<Vec<u8>, String> {
    let mut decoder = ruzstd::decoding::StreamingDecoder::new(data).map_err(|e| format!("mrs: zstd: {e:?}"))?;
    let mut out = Vec::new();
    decoder.read_to_end(&mut out).map_err(|e| format!("mrs: zstd: {e}"))?;
    Ok(out)
}

/// What an `.mrs` file holds.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MrsContent {
    /// mihomo domain-set entries (`example.com`, `+.example.com`, `*.example.com`).
    Domain(Vec<String>),
    /// CIDRs.
    IpCidr(Vec<String>),
}

pub fn read_mrs(data: &[u8]) -> Result<MrsContent, String> {
    let raw = decompress_zstd(data)?;
    let mut r = Reader { buf: &raw, pos: 0 };
    if r.take(4)? != b"MRS\x01" {
        return Err("not an MRS v1 file".into());
    }
    let behavior = r.u8()?;
    let _count = r.be_i64()?;
    let extra = r.be_i64()?;
    if extra < 0 {
        return Err("mrs: invalid extra length".into());
    }
    r.take(extra as usize)?;
    match behavior {
        0 => {
            if r.u8()? != 1 {
                return Err("mrs: unsupported domain set version".into());
            }
            let slice = |r: &mut Reader| -> Result<Vec<u64>, String> {
                let n = r.be_i64()?;
                if n < 1 || (n as usize).saturating_mul(8) > r.buf.len() - r.pos {
                    return Err("mrs: invalid length".into());
                }
                r.u64_slice(n as usize)
            };
            let leaves = slice(&mut r)?;
            let bitmap = slice(&mut r)?;
            let n = r.be_i64()?;
            if n < 1 {
                return Err("mrs: invalid length".into());
            }
            let labels = r.take(n as usize)?;
            Ok(MrsContent::Domain(louds_keys(&leaves, &bitmap, labels)?))
        }
        1 => {
            if r.u8()? != 1 {
                return Err("mrs: unsupported IP set version".into());
            }
            let n = r.be_i64()?;
            if n < 1 || (n as usize).saturating_mul(32) > r.buf.len() - r.pos {
                return Err("mrs: invalid length".into());
            }
            let mut out = Vec::new();
            for _ in 0..n {
                let mut from = [0u8; 16];
                from.copy_from_slice(r.take(16)?);
                let mut to = [0u8; 16];
                to.copy_from_slice(r.take(16)?);
                for net in range_to_nets(IpAddr::from(from), IpAddr::from(to))? {
                    out.push(net.to_string());
                }
            }
            Ok(MrsContent::IpCidr(out))
        }
        2 => Err("mrs: classical rule sets have no MRS form".into()),
        b => Err(format!("mrs: unknown behavior {b}")),
    }
}

/// The supported item types of an SRS default rule (`common/srs/binary.go`).
const SRS_NAMES: [&str; 24] = [
    "query_type",
    "network",
    "domain",
    "domain_keyword",
    "domain_regex",
    "source_ip_cidr",
    "ip_cidr",
    "source_port",
    "source_port_range",
    "port",
    "port_range",
    "process_name",
    "process_path",
    "package_name",
    "wifi_ssid",
    "wifi_bssid",
    "adguard_domain",
    "process_path_regex",
    "network_type",
    "network_is_expensive",
    "network_is_constrained",
    "network_interface_address",
    "default_interface_address",
    "package_name_regex",
];

fn srs_strings(r: &mut Reader) -> Result<Vec<Value>, String> {
    let n = r.count(1)?;
    let mut out = Vec::with_capacity(n);
    for _ in 0..n {
        let len = r.count(1)?;
        let s = std::str::from_utf8(r.take(len)?).map_err(|_| "srs: invalid UTF-8")?;
        out.push(Value::String(s.to_string()));
    }
    Ok(out)
}

fn srs_u16s(r: &mut Reader) -> Result<Vec<Value>, String> {
    let n = r.count(2)?;
    let mut out = Vec::with_capacity(n);
    for _ in 0..n {
        let b = r.take(2)?;
        out.push(json!(u16::from_be_bytes([b[0], b[1]])));
    }
    Ok(out)
}

fn srs_ip_set(r: &mut Reader) -> Result<Vec<Value>, String> {
    if r.u8()? != 1 {
        return Err("srs: unsupported IP set version".into());
    }
    let n = r.be_u64()? as usize;
    if n.saturating_mul(10) > r.buf.len() - r.pos {
        return Err("srs: IP set count larger than the data".into());
    }
    let mut out = Vec::new();
    let addr = |r: &mut Reader| -> Result<IpAddr, String> {
        let len = r.uvarint()?;
        let b = r.take(len as usize)?;
        match len {
            4 => Ok(IpAddr::from(<[u8; 4]>::try_from(b).map_err(|_| "srs: bad address")?)),
            16 => Ok(IpAddr::from(<[u8; 16]>::try_from(b).map_err(|_| "srs: bad address")?)),
            _ => Err("srs: bad address length".to_string()),
        }
    };
    for _ in 0..n {
        let from = addr(r)?;
        let to = addr(r)?;
        for net in range_to_nets(from, to)? {
            out.push(Value::String(net.to_string()));
        }
    }
    Ok(out)
}

/// sing-box's domain matcher keys: a leading `\r` (prefix label) marks a
/// raw suffix (`.example.com` = subdomains only), a leading `\n` (root
/// label) a suffix matching the name and its subdomains, anything else an
/// exact name. Dumped as `domain` / `domain_suffix`, as `Matcher.Dump` does.
fn srs_domains(r: &mut Reader) -> Result<(Vec<Value>, Vec<Value>), String> {
    let _version = r.u8()?;
    let n = r.count(8)?;
    let leaves = r.u64_slice(n)?;
    let n = r.count(8)?;
    let bitmap = r.u64_slice(n)?;
    let n = r.count(1)?;
    let labels = r.take(n)?;
    let mut domains = std::collections::BTreeSet::new();
    let mut prefixes = std::collections::BTreeSet::new();
    let mut suffixes = Vec::new();
    for key in louds_keys(&leaves, &bitmap, labels)? {
        if let Some(rest) = key.strip_prefix('\r') {
            prefixes.insert(rest.to_string());
        } else if let Some(rest) = key.strip_prefix('\n') {
            suffixes.push(rest.to_string());
        } else {
            domains.insert(key);
        }
    }
    for prefix in prefixes {
        if let Some(root) = prefix.strip_prefix('.') {
            if domains.remove(root) {
                suffixes.push(root.to_string());
                continue;
            }
        }
        suffixes.push(prefix);
    }
    Ok((domains.into_iter().map(Value::String).collect(), suffixes.into_iter().map(Value::String).collect()))
}

fn srs_rule(r: &mut Reader, depth: u32) -> Result<Value, String> {
    if depth > 100 {
        return Err("srs: logical rule nested too deep".into());
    }
    match r.u8()? {
        0 => {
            let mut rule = Map::new();
            loop {
                let item = r.u8()?;
                let name = SRS_NAMES.get(item as usize).copied();
                let value = match item {
                    0xff => {
                        rule.insert("invert".into(), Value::Bool(r.u8()? != 0));
                        return Ok(Value::Object(rule));
                    }
                    2 => {
                        let (domains, suffixes) = srs_domains(r)?;
                        if !domains.is_empty() {
                            rule.insert("domain".into(), Value::Array(domains));
                        }
                        if !suffixes.is_empty() {
                            rule.insert("domain_suffix".into(), Value::Array(suffixes));
                        }
                        continue;
                    }
                    1 | 3 | 4 | 8 | 10 | 11 | 12 | 13 | 14 | 15 | 17 | 23 => Value::Array(srs_strings(r)?),
                    5 | 6 => Value::Array(srs_ip_set(r)?),
                    7 | 9 => Value::Array(srs_u16s(r)?),
                    0 => {
                        srs_u16s(r)?;
                        return Err("srs: query_type (a DNS rule item) cannot route connections".into());
                    }
                    n => {
                        return Err(match name {
                            Some(name) => format!("srs: rule item {name} is not supported"),
                            None => format!("srs: unknown rule item type {n}"),
                        })
                    }
                };
                rule.insert(name.unwrap_or_default().into(), value);
            }
        }
        1 => {
            let mode = match r.u8()? {
                0 => "and",
                1 => "or",
                m => return Err(format!("srs: unknown logical mode {m}")),
            };
            let n = r.count(1)?;
            let mut rules = Vec::with_capacity(n);
            for _ in 0..n {
                rules.push(srs_rule(r, depth + 1)?);
            }
            let invert = r.u8()? != 0;
            Ok(json!({"type": "logical", "mode": mode, "rules": rules, "invert": invert}))
        }
        t => Err(format!("srs: unknown rule type {t}")),
    }
}

/// An SRS file as the source form's JSON (`{"version": n, "rules": [...]}`).
pub fn read_srs(data: &[u8]) -> Result<Value, String> {
    if data.len() < 4 || &data[..3] != b"SRS" {
        return Err("not a sing-box rule-set file".into());
    }
    let version = data[3];
    if version == 0 || version > 5 {
        return Err(format!("srs: unsupported version {version}"));
    }
    let raw = decompress_zlib(&data[4..])?;
    let mut r = Reader { buf: &raw, pos: 0 };
    let n = r.count(1)?;
    let mut rules = Vec::with_capacity(n);
    for i in 0..n {
        rules.push(srs_rule(&mut r, 0).map_err(|e| format!("rule {i}: {e}"))?);
    }
    Ok(json!({"version": version, "rules": rules}))
}

/// The entries of a mihomo `text` provider.
pub fn text_lines(data: &[u8]) -> Result<Vec<String>, String> {
    let text = std::str::from_utf8(data).map_err(|_| "rule provider: not UTF-8 text")?;
    Ok(text.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#') && !l.starts_with("//")).map(str::to_string).collect())
}

/// The entries of a mihomo `yaml` provider (`payload:` or `rules:`).
pub fn yaml_lines(data: &[u8]) -> Result<Vec<String>, String> {
    let text = std::str::from_utf8(data).map_err(|_| "rule provider: not UTF-8 text")?;
    let value: Value = serde_yaml::from_str(text).map_err(|e| format!("rule provider: {e}"))?;
    let list = value.get("payload").or_else(|| value.get("rules")).ok_or("rule provider: no payload")?;
    let Value::Array(items) = list else { return Err("rule provider: payload is not a list".into()) };
    items
        .iter()
        .filter(|v| !v.is_null())
        .map(|v| match v {
            Value::String(s) => Ok(s.trim().to_string()),
            Value::Number(n) => Ok(n.to_string()),
            other => Err(format!("rule provider: entry {other} is not a string")),
        })
        .filter(|r| !matches!(r, Ok(s) if s.is_empty()))
        .collect()
}

#[cfg(test)]
pub(crate) mod testdata {
    //! Writers for the binary formats, following the Go encoders.

    /// sing-box's / mihomo's succinct set over already-reversed, sorted, unique keys.
    pub fn louds(keys: &[Vec<u8>]) -> (Vec<u64>, Vec<u64>, Vec<u8>) {
        fn set(bm: &mut Vec<u64>, i: usize) {
            while i >> 6 >= bm.len() {
                bm.push(0);
            }
            bm[i >> 6] |= 1 << (i & 63);
        }
        let (mut leaves, mut bitmap, mut labels) = (Vec::new(), Vec::new(), Vec::new());
        let mut l_idx = 0usize;
        let mut queue = vec![(0usize, keys.len(), 0usize)];
        let mut i = 0;
        while i < queue.len() {
            let (mut s, e, col) = queue[i];
            if col == keys[s].len() {
                s += 1;
                set(&mut leaves, i);
            }
            let mut j = s;
            while j < e {
                let frm = j;
                while j < e && keys[j][col] == keys[frm][col] {
                    j += 1;
                }
                queue.push((frm, j, col + 1));
                labels.push(keys[frm][col]);
                while l_idx >> 6 >= bitmap.len() {
                    bitmap.push(0);
                }
                l_idx += 1;
            }
            set(&mut bitmap, l_idx);
            l_idx += 1;
            i += 1;
        }
        (leaves, bitmap, labels)
    }

    pub fn reversed_sorted(keys: &[&str]) -> Vec<Vec<u8>> {
        let mut out: Vec<Vec<u8>> = keys.iter().map(|k| k.chars().rev().collect::<String>().into_bytes()).collect();
        out.sort();
        out.dedup();
        out
    }

    fn uvarint(out: &mut Vec<u8>, mut v: u64) {
        while v >= 0x80 {
            out.push((v as u8) | 0x80);
            v >>= 7;
        }
        out.push(v as u8);
    }

    pub enum Item<'a> {
        Strings(u8, &'a [&'a str]),
        Domains(&'a [&'a str], &'a [&'a str]),
        Ips(u8, &'a [(&'a str, &'a str)]),
        Ports(u8, &'a [u16]),
        Raw(u8),
    }

    pub fn srs(rules: &[(&[Item], bool)]) -> Vec<u8> {
        let mut body = Vec::new();
        uvarint(&mut body, rules.len() as u64);
        for (items, invert) in rules {
            body.push(0);
            for item in items.iter() {
                match item {
                    Item::Strings(t, values) => {
                        body.push(*t);
                        uvarint(&mut body, values.len() as u64);
                        for v in values.iter() {
                            uvarint(&mut body, v.len() as u64);
                            body.extend_from_slice(v.as_bytes());
                        }
                    }
                    Item::Domains(domains, suffixes) => {
                        body.push(2);
                        let mut keys: Vec<String> = domains.iter().map(|d| d.to_string()).collect();
                        for s in suffixes.iter() {
                            if s.starts_with('.') {
                                keys.push(format!("\r{s}"));
                            } else {
                                keys.push(format!("\n{s}"));
                            }
                        }
                        let refs: Vec<&str> = keys.iter().map(String::as_str).collect();
                        let (leaves, bitmap, labels) = louds(&reversed_sorted(&refs));
                        body.push(1);
                        for words in [&leaves, &bitmap] {
                            uvarint(&mut body, words.len() as u64);
                            for w in words.iter() {
                                body.extend_from_slice(&w.to_be_bytes());
                            }
                        }
                        uvarint(&mut body, labels.len() as u64);
                        body.extend_from_slice(&labels);
                    }
                    Item::Ips(t, ranges) => {
                        body.push(*t);
                        body.push(1);
                        body.extend_from_slice(&(ranges.len() as u64).to_be_bytes());
                        for (from, to) in ranges.iter() {
                            for a in [from, to] {
                                let bytes = match a.parse::<std::net::IpAddr>().unwrap() {
                                    std::net::IpAddr::V4(v4) => v4.octets().to_vec(),
                                    std::net::IpAddr::V6(v6) => v6.octets().to_vec(),
                                };
                                uvarint(&mut body, bytes.len() as u64);
                                body.extend_from_slice(&bytes);
                            }
                        }
                    }
                    Item::Ports(t, ports) => {
                        body.push(*t);
                        uvarint(&mut body, ports.len() as u64);
                        for p in ports.iter() {
                            body.extend_from_slice(&p.to_be_bytes());
                        }
                    }
                    Item::Raw(t) => body.push(*t),
                }
            }
            body.push(0xff);
            body.push(u8::from(*invert));
        }
        let mut out = b"SRS\x03".to_vec();
        let mut enc = flate2::write::ZlibEncoder::new(Vec::new(), flate2::Compression::default());
        std::io::Write::write_all(&mut enc, &body).unwrap();
        out.extend_from_slice(&enc.finish().unwrap());
        out
    }

    /// An uncompressed MRS payload wrapped in a stored (raw-block) zstd frame.
    pub fn zstd_stored(payload: &[u8]) -> Vec<u8> {
        let mut out = vec![0x28, 0xb5, 0x2f, 0xfd];
        // Frame header: single segment, 4-byte content size.
        out.push(0b1010_0000);
        out.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        let chunks: Vec<&[u8]> = if payload.is_empty() { vec![&[][..]] } else { payload.chunks(100_000).collect() };
        for (i, chunk) in chunks.iter().enumerate() {
            let last = u32::from(i + 1 == chunks.len());
            let header = last | ((chunk.len() as u32) << 3); // block type 0 = raw
            out.extend_from_slice(&header.to_le_bytes()[..3]);
            out.extend_from_slice(chunk);
        }
        out
    }

    pub fn mrs_domain(entries: &[&str]) -> Vec<u8> {
        // DomainSetBuilder.Insert: "+.x" inserts "x" and "+.x"; ".x" inserts "+.x".
        let mut keys: Vec<String> = Vec::new();
        for e in entries {
            if let Some(rest) = e.strip_prefix("+.") {
                keys.push(rest.to_string());
                keys.push(e.to_string());
            } else if let Some(rest) = e.strip_prefix('.') {
                keys.push(format!("+.{rest}"));
            } else {
                keys.push(e.to_string());
            }
        }
        let refs: Vec<&str> = keys.iter().map(String::as_str).collect();
        let (leaves, bitmap, labels) = louds(&reversed_sorted(&refs));
        let mut p = b"MRS\x01".to_vec();
        p.push(0);
        p.extend_from_slice(&(entries.len() as i64).to_be_bytes());
        p.extend_from_slice(&0i64.to_be_bytes());
        p.push(1);
        for words in [&leaves, &bitmap] {
            p.extend_from_slice(&(words.len() as i64).to_be_bytes());
            for w in words.iter() {
                p.extend_from_slice(&w.to_be_bytes());
            }
        }
        p.extend_from_slice(&(labels.len() as i64).to_be_bytes());
        p.extend_from_slice(&labels);
        zstd_stored(&p)
    }

    pub fn mrs_ipcidr(ranges: &[(&str, &str)]) -> Vec<u8> {
        let mut p = b"MRS\x01".to_vec();
        p.push(1);
        p.extend_from_slice(&(ranges.len() as i64).to_be_bytes());
        p.extend_from_slice(&0i64.to_be_bytes());
        p.push(1);
        p.extend_from_slice(&(ranges.len() as i64).to_be_bytes());
        for (from, to) in ranges {
            for a in [from, to] {
                let v6 = match a.parse::<std::net::IpAddr>().unwrap() {
                    std::net::IpAddr::V4(v4) => v4.to_ipv6_mapped(),
                    std::net::IpAddr::V6(v6) => v6,
                };
                p.extend_from_slice(&v6.octets());
            }
        }
        zstd_stored(&p)
    }
}

#[cfg(test)]
mod tests {
    use super::testdata::*;
    use super::*;

    #[test]
    fn louds_round_trips() {
        let keys = ["example.com", "a.example.com", "b.org", "例子.cn"];
        let (leaves, bitmap, labels) = louds(&reversed_sorted(&keys));
        let mut got = louds_keys(&leaves, &bitmap, &labels).unwrap();
        got.sort();
        let mut want: Vec<String> = keys.iter().map(|s| s.to_string()).collect();
        want.sort();
        assert_eq!(got, want);
        assert!(louds_keys(&[], &[], &[]).unwrap().is_empty());
        assert!(louds_keys(&[1], &[0b10], &[]).is_err());
    }

    #[test]
    fn srs_items_become_source_json() {
        let file = srs(&[
            (
                &[
                    Item::Domains(&["exact.example.com"], &["example.org", ".sub.test"]),
                    Item::Strings(3, &["keyword"]),
                    Item::Ips(6, &[("10.0.0.0", "10.255.255.255"), ("2001:db8::", "2001:db8::ffff")]),
                    Item::Ports(9, &[443, 8443]),
                    Item::Strings(10, &["1000:2000"]),
                    Item::Strings(1, &["tcp"]),
                    Item::Strings(13, &["com.example"]),
                ],
                false,
            ),
            (&[Item::Strings(4, &[r"^ad\."])], true),
        ]);
        let v = read_srs(&file).unwrap();
        assert_eq!(v["version"], json!(3));
        let r0 = &v["rules"][0];
        assert_eq!(r0["domain"], json!(["exact.example.com"]));
        let mut suffixes: Vec<String> = r0["domain_suffix"].as_array().unwrap().iter().map(|s| s.as_str().unwrap().to_string()).collect();
        suffixes.sort();
        assert_eq!(suffixes, [".sub.test", "example.org"]);
        assert_eq!(r0["domain_keyword"], json!(["keyword"]));
        assert_eq!(r0["ip_cidr"], json!(["10.0.0.0/8", "2001:db8::/112"]));
        assert_eq!(r0["port"], json!([443, 8443]));
        assert_eq!(r0["port_range"], json!(["1000:2000"]));
        assert_eq!(r0["network"], json!(["tcp"]));
        assert_eq!(r0["package_name"], json!(["com.example"]));
        assert_eq!(r0["invert"], json!(false));
        assert_eq!(v["rules"][1]["invert"], json!(true));
        // Read as written; the importer refuses what it cannot express.
        assert_eq!(read_srs(&srs(&[(&[Item::Strings(14, &["home"])], false)])).unwrap()["rules"][0]["wifi_ssid"], json!(["home"]));
        assert!(read_srs(&srs(&[(&[Item::Raw(19)], false)])).unwrap_err().contains("network_is_expensive"));
        assert!(read_srs(b"SRS\x09").is_err());
        assert!(read_srs(b"JSON").is_err());
    }

    #[test]
    fn mrs_domain_and_ipcidr() {
        let file = mrs_domain(&["+.example.com", ".sub.test", "exact.org", "*.wild.net"]);
        let MrsContent::Domain(mut keys) = read_mrs(&file).unwrap() else { panic!() };
        keys.sort();
        assert_eq!(keys, ["*.wild.net", "+.example.com", "+.sub.test", "example.com", "exact.org"]);
        let file = mrs_ipcidr(&[("1.0.1.0", "1.0.1.255"), ("2400:3200::", "2400:3200:ffff:ffff:ffff:ffff:ffff:ffff")]);
        assert_eq!(read_mrs(&file).unwrap(), MrsContent::IpCidr(vec!["1.0.1.0/24".into(), "2400:3200::/32".into()]));
        assert!(read_mrs(&zstd_stored(b"MRS\x01\x02")).is_err());
        assert!(read_mrs(&zstd_stored(b"XXXX")).is_err());
        assert!(read_mrs(b"not zstd").is_err());
    }

    #[test]
    fn text_and_yaml_providers() {
        assert_eq!(text_lines(b"# c\n// c\n a.com \n\n+.b.org\n").unwrap(), ["a.com", "+.b.org"]);
        assert_eq!(yaml_lines(b"payload:\n  - 'a.com'\n  - \"+.b.org\"\n  - 1.2.3.0/24\n").unwrap(), ["a.com", "+.b.org", "1.2.3.0/24"]);
        assert!(yaml_lines(b"other: 1\n").is_err());
        assert!(yaml_lines(b"payload: 3\n").is_err());
    }
}
