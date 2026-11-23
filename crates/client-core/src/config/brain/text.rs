//! Text helpers for subscriptions and share links: Base64 (standard and
//! URL-safe alphabets, padded or not, whitespace ignored), percent decoding,
//! and a URL splitter that reads a link the way Go's `net/url` reads the
//! parts share links use (mihomo's `ConvertsV2Ray` and libXray parse links
//! with it).

/// Decodes Base64 in either alphabet, with or without padding; spaces and
/// line breaks are skipped (subscriptions wrap the body). `None` when the
/// text is not Base64.
pub fn base64_decode(input: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(input.len() / 4 * 3 + 3);
    let mut acc: u32 = 0;
    let mut bits: u32 = 0;
    let mut padded = false;
    let mut digits = 0usize;
    for c in input.bytes() {
        let v: u32 = match c {
            b'A'..=b'Z' => (c - b'A') as u32,
            b'a'..=b'z' => (c - b'a') as u32 + 26,
            b'0'..=b'9' => (c - b'0') as u32 + 52,
            b'+' | b'-' => 62,
            b'/' | b'_' => 63,
            b'=' => {
                padded = true;
                continue;
            }
            b' ' | b'\n' | b'\r' | b'\t' => continue,
            _ => return None,
        };
        if padded {
            return None;
        }
        digits += 1;
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1u32 << bits) - 1;
        }
    }
    // One digit left over (6 bits) is not a whole byte in any encoding.
    if digits == 0 || bits >= 6 {
        return None;
    }
    Some(out)
}

/// Base64 that decodes to UTF-8 text.
pub fn base64_text(input: &str) -> Option<String> {
    base64_decode(input).and_then(|b| String::from_utf8(b).ok())
}

fn hex(c: u8) -> Option<u8> {
    match c {
        b'0'..=b'9' => Some(c - b'0'),
        b'a'..=b'f' => Some(c - b'a' + 10),
        b'A'..=b'F' => Some(c - b'A' + 10),
        _ => None,
    }
}

/// Percent decoding (`%XX`); with `plus_space`, `+` is a space (query
/// strings, as Go's `QueryUnescape`). An invalid escape or a result that is
/// not UTF-8 is an error, as in Go.
pub fn percent_decode(input: &str, plus_space: bool) -> Result<String, String> {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' => {
                let pair = (bytes.get(i + 1).copied().and_then(hex), bytes.get(i + 2).copied().and_then(hex));
                match pair {
                    (Some(h), Some(l)) => {
                        out.push(h * 16 + l);
                        i += 3;
                    }
                    _ => return Err(format!("invalid escape in {input:?}")),
                }
            }
            b'+' if plus_space => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8(out).map_err(|_| format!("escape is not UTF-8 in {input:?}"))
}

/// A share link split into its parts (decoded, except `userinfo_raw` and
/// `raw_path`).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Url {
    /// Lowercase.
    pub scheme: String,
    /// The user information as written (before `@`), `None` without `@`.
    pub userinfo_raw: Option<String>,
    pub username: String,
    /// `Some` when the user information has a `:`.
    pub password: Option<String>,
    /// Host name, without the brackets of an IPv6 literal.
    pub host: String,
    /// The port as written; empty when there is none.
    pub port: String,
    pub path: String,
    pub raw_path: String,
    /// Query pairs in order, decoded (`+` is a space).
    pub query: Vec<(String, String)>,
    pub fragment: String,
}

impl Url {
    /// The first value of a query key, empty when absent (Go's `Query().Get`).
    pub fn get(&self, key: &str) -> &str {
        self.query.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str()).unwrap_or("")
    }

    /// Every value of a query key, in order.
    pub fn all(&self, key: &str) -> Vec<&str> {
        self.query.iter().filter(|(k, _)| k == key).map(|(_, v)| v.as_str()).collect()
    }

    pub fn has(&self, key: &str) -> bool {
        self.query.iter().any(|(k, _)| k == key)
    }
}

/// Splits a query string into decoded pairs; pairs with an invalid escape
/// are left out, as Go's `url.Query()` leaves them out.
pub fn parse_query(raw: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    for part in raw.split('&') {
        if part.is_empty() {
            continue;
        }
        let (k, v) = match part.split_once('=') {
            Some((k, v)) => (k, v),
            None => (part, ""),
        };
        if let (Ok(k), Ok(v)) = (percent_decode(k, true), percent_decode(v, true)) {
            out.push((k, v));
        }
    }
    out
}

/// Parses `scheme://[userinfo@]host[:port][/path][?query][#fragment]`.
pub fn parse_url(line: &str) -> Result<Url, String> {
    let (scheme, rest) = line.split_once("://").ok_or_else(|| "not a link (no \"://\")".to_string())?;
    let scheme = scheme.trim().to_ascii_lowercase();
    if scheme.is_empty() || !scheme.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'+' || b == b'-' || b == b'.') {
        return Err(format!("invalid scheme {scheme:?}"));
    }
    let (rest, fragment) = match rest.split_once('#') {
        Some((r, f)) => (r, percent_decode(f, false)?),
        None => (rest, String::new()),
    };
    let (rest, query) = match rest.split_once('?') {
        Some((r, q)) => (r, parse_query(q)),
        None => (rest, Vec::new()),
    };
    let (authority, raw_path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, ""),
    };
    let (userinfo_raw, hostport) = match authority.rfind('@') {
        Some(i) => (Some(&authority[..i]), &authority[i + 1..]),
        None => (None, authority),
    };
    let (username, password) = match userinfo_raw {
        Some(info) => match info.split_once(':') {
            Some((u, p)) => (percent_decode(u, false)?, Some(percent_decode(p, false)?)),
            None => (percent_decode(info, false)?, None),
        },
        None => (String::new(), None),
    };
    let (host, port) = if let Some(stripped) = hostport.strip_prefix('[') {
        let end = stripped.find(']').ok_or_else(|| format!("unclosed IPv6 literal in {hostport:?}"))?;
        let host = &stripped[..end];
        let after = &stripped[end + 1..];
        let port = match after.strip_prefix(':') {
            Some(p) => p,
            None if after.is_empty() => "",
            None => return Err(format!("invalid host {hostport:?}")),
        };
        (host.to_string(), port.to_string())
    } else {
        match hostport.rfind(':') {
            Some(i) => (hostport[..i].to_string(), hostport[i + 1..].to_string()),
            None => (hostport.to_string(), String::new()),
        }
    };
    if !port.bytes().all(|b| b.is_ascii_digit()) {
        return Err(format!("invalid port {port:?}"));
    }
    let host = percent_decode(&host, false)?;
    Ok(Url {
        scheme,
        userinfo_raw: userinfo_raw.map(str::to_string),
        username,
        password,
        host,
        port,
        path: percent_decode(raw_path, false)?,
        raw_path: raw_path.to_string(),
        query,
        fragment,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_both_alphabets() {
        assert_eq!(base64_text("aGVsbG8gd29ybGQ=").as_deref(), Some("hello world"));
        assert_eq!(base64_text("aGVsbG8gd29ybGQ").as_deref(), Some("hello world"));
        assert_eq!(base64_decode("-_8").unwrap(), vec![0xfb, 0xff]);
        assert_eq!(base64_decode("+/8=").unwrap(), vec![0xfb, 0xff]);
        assert_eq!(base64_text("aGVs\nbG8=").as_deref(), Some("hello"));
        assert!(base64_decode("a").is_none());
        assert!(base64_decode("ab=c").is_none());
        assert!(base64_decode("not base64!").is_none());
        assert!(base64_decode("").is_none());
    }

    #[test]
    fn urls_split_like_go() {
        let u = parse_url("vless://id%40x@[2001:db8::1]:443/p%20a?type=ws&path=%2Fws%3Fed%3D2048&a=b+c#n%20ame").unwrap();
        assert_eq!(u.scheme, "vless");
        assert_eq!(u.username, "id@x");
        assert_eq!(u.userinfo_raw.as_deref(), Some("id%40x"));
        assert_eq!(u.password, None);
        assert_eq!((u.host.as_str(), u.port.as_str()), ("2001:db8::1", "443"));
        assert_eq!(u.path, "/p a");
        assert_eq!(u.get("path"), "/ws?ed=2048");
        assert_eq!(u.get("a"), "b c");
        assert_eq!(u.get("missing"), "");
        assert_eq!(u.fragment, "n ame");
        let u = parse_url("SS://user:pa%3Ass@host.example:8388").unwrap();
        assert_eq!((u.scheme.as_str(), u.username.as_str(), u.password.as_deref()), ("ss", "user", Some("pa:ss")));
        assert_eq!((u.host.as_str(), u.port.as_str()), ("host.example", "8388"));
        assert!(parse_url("vless://a@host:44x").is_err());
        assert!(parse_url("no scheme").is_err());
        assert!(parse_url("trojan://p@h:1#%zz").is_err());
    }
}
