//! The geo resources the existing geo policy ([`crate::geo`]) installs, read
//! in their own formats:
//!
//! - `geosite.dat` (v2fly/Xray `GeoSiteList` protobuf, as MetaCubeX
//!   meta-rules-dat ships it): categories by `country_code`, each domain with
//!   a type (0 plain = keyword, 1 regex, 2 root domain = suffix, 3 full) and
//!   attributes (`@ads`).
//! - `geoip.dat` (`GeoIPList` protobuf): CIDRs by code.
//! - `geoip.metadb` (MaxMind DB, database type `Meta-geoip0`: the value is a
//!   code or a list of codes; `sing-geoip` and GeoLite2-Country layouts are
//!   read too), see [`super::mmdb`].
//!
//! Both `.dat` files are indexed once (category name → byte range) and a
//! category is decoded only when a rule names it.

use std::collections::HashMap;
use std::sync::Arc;

use super::ip::IpNet;

/// A protobuf wire-format reader (only what these messages use).
pub(crate) struct Pb<'a> {
    buf: &'a [u8],
    pos: usize,
}

pub(crate) enum PbValue<'a> {
    Varint(u64),
    Bytes(&'a [u8]),
    Fixed,
}

impl<'a> Pb<'a> {
    pub(crate) fn new(buf: &'a [u8]) -> Pb<'a> {
        Pb { buf, pos: 0 }
    }

    fn varint(&mut self) -> Result<u64, String> {
        let mut out = 0u64;
        for shift in (0..64).step_by(7) {
            let b = *self.buf.get(self.pos).ok_or("protobuf: truncated varint")?;
            self.pos += 1;
            out |= u64::from(b & 0x7f) << shift;
            if b < 0x80 {
                return Ok(out);
            }
        }
        Err("protobuf: varint too long".into())
    }

    /// The next field (number, value), or `None` at the end.
    pub(crate) fn next(&mut self) -> Result<Option<(u32, PbValue<'a>)>, String> {
        if self.pos >= self.buf.len() {
            return Ok(None);
        }
        let key = self.varint()?;
        let field = (key >> 3) as u32;
        let value = match key & 7 {
            0 => PbValue::Varint(self.varint()?),
            2 => {
                let len = self.varint()? as usize;
                let end = self.pos.checked_add(len).filter(|&e| e <= self.buf.len()).ok_or("protobuf: truncated field")?;
                let bytes = &self.buf[self.pos..end];
                self.pos = end;
                PbValue::Bytes(bytes)
            }
            1 => {
                self.pos = self.pos.checked_add(8).filter(|&e| e <= self.buf.len()).ok_or("protobuf: truncated field")?;
                PbValue::Fixed
            }
            5 => {
                self.pos = self.pos.checked_add(4).filter(|&e| e <= self.buf.len()).ok_or("protobuf: truncated field")?;
                PbValue::Fixed
            }
            w => return Err(format!("protobuf: unsupported wire type {w}")),
        };
        Ok(Some((field, value)))
    }
}

fn str_of(bytes: &[u8]) -> Result<&str, String> {
    std::str::from_utf8(bytes).map_err(|_| "geo data: invalid UTF-8".to_string())
}

/// Category name (lowercase) → the entry's message bytes.
fn index_entries(data: &[u8]) -> Result<HashMap<String, (usize, usize)>, String> {
    let mut out = HashMap::new();
    let base = data.as_ptr() as usize;
    let mut list = Pb::new(data);
    while let Some((field, value)) = list.next()? {
        if let (1, PbValue::Bytes(entry)) = (field, value) {
            let mut fields = Pb::new(entry);
            while let Some((f, v)) = fields.next()? {
                if let (1, PbValue::Bytes(code)) = (f, v) {
                    let start = entry.as_ptr() as usize - base;
                    out.entry(str_of(code)?.to_ascii_lowercase()).or_insert((start, entry.len()));
                    break;
                }
            }
        }
    }
    Ok(out)
}

/// One geosite domain.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SiteDomain {
    Keyword(String),
    Regex(String),
    Suffix(String),
    Full(String),
}

pub struct GeoSiteDb {
    data: Arc<[u8]>,
    index: HashMap<String, (usize, usize)>,
}

impl GeoSiteDb {
    pub fn parse(data: Arc<[u8]>) -> Result<GeoSiteDb, String> {
        let index = index_entries(&data)?;
        if index.is_empty() {
            return Err("geosite.dat has no categories".into());
        }
        Ok(GeoSiteDb { data, index })
    }

    pub fn categories(&self) -> usize {
        self.index.len()
    }

    /// The domains of `name` (`cn`, `cn@ads`, `geolocation-!cn`; `@attr`
    /// filters keep the domains carrying every attribute named).
    pub fn domains(&self, name: &str) -> Result<Vec<SiteDomain>, String> {
        let mut parts = name.split('@');
        let category = parts.next().unwrap_or_default().trim().to_ascii_lowercase();
        let attrs: Vec<String> = parts.map(|a| a.trim().to_ascii_lowercase()).filter(|a| !a.is_empty()).collect();
        let &(start, len) = self.index.get(&category).ok_or_else(|| format!("geosite category not found: {category}"))?;
        let entry = &self.data[start..start + len];
        let mut out = Vec::new();
        let mut fields = Pb::new(entry);
        while let Some((f, v)) = fields.next()? {
            let (2, PbValue::Bytes(domain)) = (f, v) else { continue };
            let mut kind = 0u64;
            let mut value = "";
            let mut have: Vec<String> = Vec::new();
            let mut d = Pb::new(domain);
            while let Some((f2, v2)) = d.next()? {
                match (f2, v2) {
                    (1, PbValue::Varint(k)) => kind = k,
                    (2, PbValue::Bytes(b)) => value = str_of(b)?,
                    (3, PbValue::Bytes(attr)) if !attrs.is_empty() => {
                        let mut a = Pb::new(attr);
                        while let Some((f3, v3)) = a.next()? {
                            if let (1, PbValue::Bytes(key)) = (f3, v3) {
                                have.push(str_of(key)?.to_ascii_lowercase());
                            }
                        }
                    }
                    _ => {}
                }
            }
            if !attrs.iter().all(|a| have.contains(a)) {
                continue;
            }
            let value = value.to_string();
            out.push(match kind {
                0 => SiteDomain::Keyword(value),
                1 => SiteDomain::Regex(value),
                2 => SiteDomain::Suffix(value),
                3 => SiteDomain::Full(value),
                k => return Err(format!("geosite {category}: unknown domain type {k}")),
            });
        }
        Ok(out)
    }
}

pub struct GeoIpDat {
    data: Arc<[u8]>,
    index: HashMap<String, (usize, usize)>,
}

impl GeoIpDat {
    pub fn parse(data: Arc<[u8]>) -> Result<GeoIpDat, String> {
        let index = index_entries(&data)?;
        if index.is_empty() {
            return Err("geoip.dat has no entries".into());
        }
        Ok(GeoIpDat { data, index })
    }

    pub fn has(&self, code: &str) -> bool {
        self.index.contains_key(&code.to_ascii_lowercase())
    }

    /// The CIDRs of `code`; `reverse_match` entries are refused (no rule
    /// syntax here asks for them and inverting needs the complement).
    pub fn nets(&self, code: &str) -> Result<Vec<IpNet>, String> {
        let code = code.to_ascii_lowercase();
        let &(start, len) = self.index.get(&code).ok_or_else(|| format!("geoip code not found: {code}"))?;
        let entry = &self.data[start..start + len];
        let mut out = Vec::new();
        let mut fields = Pb::new(entry);
        while let Some((f, v)) = fields.next()? {
            match (f, v) {
                (2, PbValue::Bytes(cidr)) => {
                    let mut ip: &[u8] = &[];
                    let mut prefix = 0u64;
                    let mut c = Pb::new(cidr);
                    while let Some((f2, v2)) = c.next()? {
                        match (f2, v2) {
                            (1, PbValue::Bytes(b)) => ip = b,
                            (2, PbValue::Varint(p)) => prefix = p,
                            _ => {}
                        }
                    }
                    let addr = match ip.len() {
                        4 => std::net::IpAddr::from(<[u8; 4]>::try_from(ip).map_err(|_| "geoip: bad address")?),
                        16 => std::net::IpAddr::from(<[u8; 16]>::try_from(ip).map_err(|_| "geoip: bad address")?),
                        n => return Err(format!("geoip {code}: address of {n} bytes")),
                    };
                    out.push(IpNet::new(addr, prefix.min(128) as u8)?);
                }
                (3, PbValue::Varint(1)) => return Err(format!("geoip {code}: reverse_match entries are not supported")),
                _ => {}
            }
        }
        Ok(out)
    }
}

#[cfg(test)]
pub(crate) mod testdata {
    //! Builders for small geo files in the real wire formats.

    fn varint(out: &mut Vec<u8>, mut v: u64) {
        while v >= 0x80 {
            out.push((v as u8) | 0x80);
            v >>= 7;
        }
        out.push(v as u8);
    }

    pub fn field_bytes(out: &mut Vec<u8>, field: u32, bytes: &[u8]) {
        varint(out, u64::from(field) << 3 | 2);
        varint(out, bytes.len() as u64);
        out.extend_from_slice(bytes);
    }

    pub fn field_varint(out: &mut Vec<u8>, field: u32, v: u64) {
        varint(out, u64::from(field) << 3);
        varint(out, v);
    }

    /// `(category, [(type, value, [attrs])])`.
    pub fn geosite(entries: &[(&str, &[(u64, &str, &[&str])])]) -> Vec<u8> {
        let mut list = Vec::new();
        for (code, domains) in entries {
            let mut site = Vec::new();
            field_bytes(&mut site, 1, code.as_bytes());
            for (kind, value, attrs) in domains.iter() {
                let mut d = Vec::new();
                if *kind != 0 {
                    field_varint(&mut d, 1, *kind);
                }
                field_bytes(&mut d, 2, value.as_bytes());
                for attr in attrs.iter() {
                    let mut a = Vec::new();
                    field_bytes(&mut a, 1, attr.as_bytes());
                    field_varint(&mut a, 2, 1);
                    field_bytes(&mut d, 3, &a);
                }
                field_bytes(&mut site, 2, &d);
            }
            field_bytes(&mut list, 1, &site);
        }
        list
    }

    /// `(code, [cidr])`.
    pub fn geoip(entries: &[(&str, &[&str])]) -> Vec<u8> {
        let mut list = Vec::new();
        for (code, cidrs) in entries {
            let mut e = Vec::new();
            field_bytes(&mut e, 1, code.as_bytes());
            for cidr in cidrs.iter() {
                let (a, p) = cidr.split_once('/').unwrap();
                let addr: std::net::IpAddr = a.parse().unwrap();
                let bytes = match addr {
                    std::net::IpAddr::V4(v4) => v4.octets().to_vec(),
                    std::net::IpAddr::V6(v6) => v6.octets().to_vec(),
                };
                let mut c = Vec::new();
                field_bytes(&mut c, 1, &bytes);
                field_varint(&mut c, 2, p.parse().unwrap());
                field_bytes(&mut e, 2, &c);
            }
            field_bytes(&mut list, 1, &e);
        }
        list
    }
}

#[cfg(test)]
mod tests {
    use super::testdata::*;
    use super::*;

    #[test]
    fn geosite_categories_types_and_attrs() {
        let data = geosite(&[
            ("CN", &[(2, "cn", &[]), (3, "www.example.cn", &[]), (0, "baidu", &["ads"]), (1, r"^ad\d+\.", &["ads", "x"])]),
            ("GOOGLE", &[(2, "google.com", &[])]),
        ]);
        let db = GeoSiteDb::parse(Arc::from(data)).unwrap();
        assert_eq!(db.categories(), 2);
        let cn = db.domains("cn").unwrap();
        assert_eq!(cn.len(), 4);
        assert_eq!(cn[0], SiteDomain::Suffix("cn".into()));
        assert_eq!(cn[1], SiteDomain::Full("www.example.cn".into()));
        assert_eq!(cn[2], SiteDomain::Keyword("baidu".into()));
        assert_eq!(db.domains("CN@ads").unwrap().len(), 2);
        assert_eq!(db.domains("cn@ads@x").unwrap(), vec![SiteDomain::Regex(r"^ad\d+\.".into())]);
        assert!(db.domains("nope").unwrap_err().contains("not found"));
        assert!(GeoSiteDb::parse(Arc::from(Vec::new())).is_err());
        assert!(GeoSiteDb::parse(Arc::from(vec![0x0a, 0x05, 0x01])).is_err());
    }

    #[test]
    fn geoip_dat_codes() {
        let data = geoip(&[("CN", &["1.0.1.0/24", "2400:3200::/32"]), ("PRIVATE", &["10.0.0.0/8"])]);
        let db = GeoIpDat::parse(Arc::from(data)).unwrap();
        assert!(db.has("cn") && db.has("private") && !db.has("us"));
        let nets = db.nets("Cn").unwrap();
        assert_eq!(nets.iter().map(|n| n.to_string()).collect::<Vec<_>>(), ["1.0.1.0/24", "2400:3200::/32"]);
        assert!(db.nets("us").is_err());
    }
}
