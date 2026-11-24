//! The DNS wire format (RFC 1035), as much as the brain needs: read one
//! question, build a query, read the addresses and TTL of a reply, and
//! build a reply carrying addresses. Everything else (other record types)
//! is forwarded as the upstream sent it.

use std::net::IpAddr;

pub const TYPE_A: u16 = 1;
pub const TYPE_CNAME: u16 = 5;
pub const TYPE_OPT: u16 = 41;
pub const TYPE_AAAA: u16 = 28;
pub const TYPE_SVCB: u16 = 64;
pub const TYPE_HTTPS: u16 = 65;
pub const CLASS_IN: u16 = 1;

pub const RCODE_NOERROR: u8 = 0;
pub const RCODE_FORMERR: u8 = 1;
pub const RCODE_SERVFAIL: u8 = 2;
pub const RCODE_NXDOMAIN: u8 = 3;
pub const RCODE_REFUSED: u8 = 5;

const HEADER: usize = 12;
const FLAG_QR: u16 = 0x8000;
const FLAG_RD: u16 = 0x0100;
const FLAG_RA: u16 = 0x0080;
const OPCODE_MASK: u16 = 0x7800;
/// EDNS(0) payload size the brain advertises upstream (the DNS flag day value).
const EDNS_PAYLOAD: u16 = 1232;

/// The one question of a query.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Question {
    pub id: u16,
    pub flags: u16,
    /// Lowercase, without the trailing dot ("" for the root).
    pub name: String,
    pub qtype: u16,
    pub qclass: u16,
    /// Where the question section ends in the query.
    pub end: usize,
}

fn u16_at(msg: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_be_bytes([*msg.get(at)?, *msg.get(at + 1)?]))
}

fn u32_at(msg: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_be_bytes([*msg.get(at)?, *msg.get(at + 1)?, *msg.get(at + 2)?, *msg.get(at + 3)?]))
}

/// Reads a (possibly compressed) name at `at`; returns it and the offset
/// after it in the message (after the first pointer when compressed).
pub fn read_name(msg: &[u8], at: usize) -> Option<(String, usize)> {
    let mut name = String::new();
    let mut pos = at;
    let mut after = None;
    // Every pointer must go backwards, so a message has at most len/2 of them.
    let mut jumps = 0;
    loop {
        let len = *msg.get(pos)? as usize;
        match len & 0xC0 {
            0x00 if len == 0 => {
                return Some((name, after.unwrap_or(pos + 1)));
            }
            0x00 => {
                let label = msg.get(pos + 1..pos + 1 + len)?;
                if !name.is_empty() {
                    name.push('.');
                }
                for &b in label {
                    name.push(b.to_ascii_lowercase() as char);
                }
                if name.len() > 253 {
                    return None;
                }
                pos += 1 + len;
            }
            0xC0 => {
                let target = (u16_at(msg, pos)? & 0x3FFF) as usize;
                if target >= pos || jumps > msg.len() / 2 {
                    return None;
                }
                jumps += 1;
                after.get_or_insert(pos + 2);
                pos = target;
            }
            _ => return None,
        }
    }
}

/// Reads the header and the single question of a query.
pub fn parse_query(msg: &[u8]) -> Option<Question> {
    if msg.len() < HEADER {
        return None;
    }
    let id = u16_at(msg, 0)?;
    let flags = u16_at(msg, 2)?;
    if flags & FLAG_QR != 0 || u16_at(msg, 4)? != 1 {
        return None;
    }
    let (name, pos) = read_name(msg, HEADER)?;
    let qtype = u16_at(msg, pos)?;
    let qclass = u16_at(msg, pos + 2)?;
    if name.bytes().any(|b| b < 0x21 || b > 0x7E) {
        return None;
    }
    Some(Question { id, flags, name, qtype, qclass, end: pos + 4 })
}

fn push_name(out: &mut Vec<u8>, name: &str) -> bool {
    for label in name.trim_end_matches('.').split('.').filter(|l| !l.is_empty()) {
        if label.len() > 63 {
            return false;
        }
        out.push(label.len() as u8);
        out.extend_from_slice(label.as_bytes());
    }
    out.push(0);
    true
}

/// A recursive query for `name` with an EDNS(0) record; `None` for a name
/// that cannot be encoded.
pub fn build_query(id: u16, name: &str, qtype: u16) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(HEADER + name.len() + 2 + 4 + 11);
    out.extend_from_slice(&id.to_be_bytes());
    out.extend_from_slice(&FLAG_RD.to_be_bytes());
    out.extend_from_slice(&[0, 1, 0, 0, 0, 0, 0, 1]);
    if !push_name(&mut out, name) || out.len() > HEADER + 255 {
        return None;
    }
    out.extend_from_slice(&qtype.to_be_bytes());
    out.extend_from_slice(&CLASS_IN.to_be_bytes());
    // OPT: root name, type 41, class = payload size, TTL 0, no options.
    out.push(0);
    out.extend_from_slice(&TYPE_OPT.to_be_bytes());
    out.extend_from_slice(&EDNS_PAYLOAD.to_be_bytes());
    out.extend_from_slice(&[0, 0, 0, 0, 0, 0]);
    Some(out)
}

/// What a reply says about addresses.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Answer {
    pub rcode: u8,
    pub addrs: Vec<IpAddr>,
    /// The smallest TTL of the answer records; `None` when there are none.
    pub ttl: Option<u32>,
}

/// Checks that `msg` is a reply to query `id` and reads its rcode and its
/// A/AAAA records of type `qtype` (CNAME chains are followed implicitly:
/// every address record of the answer section belongs to the question).
pub fn parse_reply(msg: &[u8], id: u16) -> Result<(u16, Answer), String> {
    if msg.len() < HEADER {
        return Err("short DNS reply".into());
    }
    let flags = u16_at(msg, 2).unwrap_or(0);
    if u16_at(msg, 0) != Some(id) || flags & FLAG_QR == 0 {
        return Err("not a reply to this query".into());
    }
    let qdcount = u16_at(msg, 4).unwrap_or(0) as usize;
    let ancount = u16_at(msg, 6).unwrap_or(0) as usize;
    let mut pos = HEADER;
    let mut qtype = 0;
    for _ in 0..qdcount {
        let (_, next) = read_name(msg, pos).ok_or("malformed question")?;
        qtype = u16_at(msg, next).ok_or("malformed question")?;
        pos = next + 4;
    }
    let mut answer = Answer { rcode: (flags & 0x000F) as u8, addrs: Vec::new(), ttl: None };
    for _ in 0..ancount {
        let (_, next) = read_name(msg, pos).ok_or("malformed answer")?;
        let rtype = u16_at(msg, next).ok_or("malformed answer")?;
        let ttl = u32_at(msg, next + 4).ok_or("malformed answer")?;
        let rdlen = u16_at(msg, next + 8).ok_or("malformed answer")? as usize;
        let rdata = msg.get(next + 10..next + 10 + rdlen).ok_or("truncated answer")?;
        pos = next + 10 + rdlen;
        match (rtype, rdlen) {
            (TYPE_A, 4) => answer.addrs.push(IpAddr::from([rdata[0], rdata[1], rdata[2], rdata[3]])),
            (TYPE_AAAA, 16) => {
                let mut octets = [0u8; 16];
                octets.copy_from_slice(rdata);
                answer.addrs.push(IpAddr::from(octets));
            }
            (TYPE_A | TYPE_AAAA | TYPE_CNAME, _) => {}
            _ => continue,
        }
        answer.ttl = Some(answer.ttl.map_or(ttl, |t| t.min(ttl)));
    }
    Ok((qtype, answer))
}

/// A reply to `query` (whose question is `q`) with `rcode` and the
/// addresses of `q`'s family, all with `ttl`.
pub fn build_reply(query: &[u8], q: &Question, rcode: u8, addrs: &[IpAddr], ttl: u32) -> Vec<u8> {
    let records: Vec<&IpAddr> = addrs
        .iter()
        .filter(|a| match q.qtype {
            TYPE_A => a.is_ipv4(),
            TYPE_AAAA => a.is_ipv6(),
            _ => false,
        })
        .collect();
    let mut out = Vec::with_capacity(q.end + records.len() * 28);
    let flags = FLAG_QR | (q.flags & (OPCODE_MASK | FLAG_RD)) | FLAG_RA | rcode as u16;
    out.extend_from_slice(&q.id.to_be_bytes());
    out.extend_from_slice(&flags.to_be_bytes());
    out.extend_from_slice(&[0, 1]);
    out.extend_from_slice(&(records.len() as u16).to_be_bytes());
    out.extend_from_slice(&[0, 0, 0, 0]);
    out.extend_from_slice(&query[HEADER..q.end]);
    for addr in records {
        // The owner name points at the question (offset 12).
        out.extend_from_slice(&[0xC0, 0x0C]);
        match addr {
            IpAddr::V4(v4) => {
                out.extend_from_slice(&TYPE_A.to_be_bytes());
                out.extend_from_slice(&CLASS_IN.to_be_bytes());
                out.extend_from_slice(&ttl.to_be_bytes());
                out.extend_from_slice(&4u16.to_be_bytes());
                out.extend_from_slice(&v4.octets());
            }
            IpAddr::V6(v6) => {
                out.extend_from_slice(&TYPE_AAAA.to_be_bytes());
                out.extend_from_slice(&CLASS_IN.to_be_bytes());
                out.extend_from_slice(&ttl.to_be_bytes());
                out.extend_from_slice(&16u16.to_be_bytes());
                out.extend_from_slice(&v6.octets());
            }
        }
    }
    out
}

/// A header-only error reply (no question) for a query that could not be
/// read; `None` when not even the ID is there.
pub fn error_reply(query: &[u8], rcode: u8) -> Option<Vec<u8>> {
    let id = u16_at(query, 0)?;
    let flags = FLAG_QR | (u16_at(query, 2).unwrap_or(0) & (OPCODE_MASK | FLAG_RD)) | FLAG_RA | rcode as u16;
    let mut out = Vec::with_capacity(HEADER);
    out.extend_from_slice(&id.to_be_bytes());
    out.extend_from_slice(&flags.to_be_bytes());
    out.extend_from_slice(&[0; 8]);
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn query_round_trip_and_reply() {
        let query = build_query(0x1234, "WWW.Example.COM.", TYPE_A).unwrap();
        let q = parse_query(&query).unwrap();
        assert_eq!((q.id, q.name.as_str(), q.qtype, q.qclass), (0x1234, "www.example.com", TYPE_A, CLASS_IN));
        let addrs: Vec<IpAddr> = vec!["1.2.3.4".parse().unwrap(), "::1".parse().unwrap(), "5.6.7.8".parse().unwrap()];
        let reply = build_reply(&query, &q, RCODE_NOERROR, &addrs, 42);
        let (qtype, answer) = parse_reply(&reply, 0x1234).unwrap();
        assert_eq!(qtype, TYPE_A);
        assert_eq!(answer.rcode, RCODE_NOERROR);
        assert_eq!(answer.addrs, vec![addrs[0], addrs[2]]);
        assert_eq!(answer.ttl, Some(42));
        assert!(parse_reply(&reply, 0x4321).is_err(), "another ID");
        assert!(parse_reply(&query, 0x1234).is_err(), "a query is not a reply");
        assert!(parse_query(&reply).is_none(), "a reply is not a query");
        let nx = build_reply(&query, &q, RCODE_NXDOMAIN, &[], 0);
        let (_, answer) = parse_reply(&nx, 0x1234).unwrap();
        assert_eq!((answer.rcode, answer.addrs.len(), answer.ttl), (RCODE_NXDOMAIN, 0, None));
        let err = error_reply(&query, RCODE_FORMERR).unwrap();
        assert_eq!(parse_reply(&err, 0x1234).unwrap().1.rcode, RCODE_FORMERR);
        assert!(build_query(1, &format!("{}.com", "a".repeat(64)), TYPE_A).is_none());
    }

    #[test]
    fn compressed_cname_chain() {
        // www.a.test CNAME b.test (compressed), b.test A 9.9.9.9, TTLs 300 / 30.
        let mut msg = vec![0xAB, 0xCD, 0x81, 0x80, 0, 1, 0, 2, 0, 0, 0, 0];
        msg.extend_from_slice(b"\x03www\x01a\x04test\x00\x00\x01\x00\x01");
        msg.extend_from_slice(&[0xC0, 0x0C, 0, 5, 0, 1, 0, 0, 1, 44, 0, 4]);
        msg.extend_from_slice(b"\x01b\xC0\x12");
        let b_at = msg.len() - 4;
        msg.extend_from_slice(&[0xC0, b_at as u8, 0, 1, 0, 1, 0, 0, 0, 30, 0, 4, 9, 9, 9, 9]);
        assert_eq!(read_name(&msg, b_at), Some(("b.test".to_string(), b_at + 4)));
        let (qtype, answer) = parse_reply(&msg, 0xABCD).unwrap();
        assert_eq!(qtype, TYPE_A);
        assert_eq!(answer.addrs, vec!["9.9.9.9".parse::<IpAddr>().unwrap()]);
        assert_eq!(answer.ttl, Some(30));
        // A pointer loop is refused.
        let mut looped = msg[..12].to_vec();
        looped.extend_from_slice(&[0xC0, 0x0C]);
        assert!(read_name(&looped, 12).is_none());
    }
}
