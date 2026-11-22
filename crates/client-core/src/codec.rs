//! Small encodings the backup and WebDAV code needs: Base64 (standard
//! alphabet, as Android's `Base64.NO_WRAP` writes and `Base64.DEFAULT` reads
//! it), canonical UUID text, and the name-based UUIDs Java's
//! `UUID.nameUUIDFromBytes` makes (version 3, MD5).

use md5::{Digest, Md5};
use sha2::Sha256;
use std::sync::atomic::{AtomicU64, Ordering};

const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// Standard Base64 with padding and no line breaks.
pub fn base64_encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = (u32::from(b[0]) << 16) | (u32::from(b[1]) << 8) | u32::from(b[2]);
        for i in 0..4 {
            if i <= chunk.len() {
                out.push(ALPHABET[((n >> (18 - 6 * i)) & 63) as usize] as char);
            } else {
                out.push('=');
            }
        }
    }
    out
}

/// Decodes Base64 as Android's `Base64.DEFAULT` does: white space is
/// skipped, padding is optional, anything else outside the alphabet fails.
pub fn base64_decode(text: &str) -> Result<Vec<u8>, String> {
    let mut out = Vec::with_capacity(text.len() / 4 * 3);
    let mut buffer = 0u32;
    let mut bits = 0;
    let mut padding = false;
    for c in text.bytes() {
        if c.is_ascii_whitespace() {
            continue;
        }
        if c == b'=' {
            padding = true;
            continue;
        }
        if padding {
            return Err("Invalid Base64 content".into());
        }
        let value = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            _ => return Err("Invalid Base64 content".into()),
        };
        buffer = (buffer << 6) | u32::from(value);
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buffer >> bits) as u8);
            buffer &= (1 << bits) - 1;
        }
    }
    // A lone sixth of a byte cannot end valid Base64.
    if bits >= 6 {
        return Err("Invalid Base64 content".into());
    }
    Ok(out)
}

fn format_uuid(b: &[u8; 16]) -> String {
    let hex: String = b.iter().map(|x| format!("{x:02x}")).collect();
    format!("{}-{}-{}-{}-{}", &hex[..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..])
}

/// Java's `UUID.nameUUIDFromBytes`: MD5 of `name` with the version (3) and
/// IETF variant bits set.
pub fn name_uuid(name: &[u8]) -> String {
    let digest = Md5::digest(name);
    let mut b = [0u8; 16];
    b.copy_from_slice(&digest);
    b[6] = (b[6] & 0x0f) | 0x30;
    b[8] = (b[8] & 0x3f) | 0x80;
    format_uuid(&b)
}

/// A random-looking version 4 UUID for file names and tokens (not for
/// secrets): SHA-256 of the clock, the process and a counter.
pub fn random_uuid() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(0);
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
    let mut hasher = Sha256::new();
    hasher.update(now.to_le_bytes());
    hasher.update(std::process::id().to_le_bytes());
    hasher.update(COUNTER.fetch_add(1, Ordering::SeqCst).to_le_bytes());
    let local = 0u8;
    hasher.update((&local as *const u8 as usize).to_le_bytes());
    let digest = hasher.finalize();
    let mut b = [0u8; 16];
    b.copy_from_slice(&digest[..16]);
    b[6] = (b[6] & 0x0f) | 0x40;
    b[8] = (b[8] & 0x3f) | 0x80;
    format_uuid(&b)
}

/// A UUID in the text `java.util.UUID.toString` gives back (lowercase,
/// 8-4-4-4-12), the only form a backup accepts.
pub fn is_canonical_uuid(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(i, b)| if [8, 13, 18, 23].contains(&i) { b == b'-' } else { b.is_ascii_digit() || (b'a'..=b'f').contains(&b) })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn base64_round_trip_and_android_leniency() {
        for len in 0..10 {
            let bytes: Vec<u8> = (0..len).map(|i| (i * 37 + 200) as u8).collect();
            assert_eq!(base64_decode(&base64_encode(&bytes)).unwrap(), bytes);
        }
        assert_eq!(base64_encode(b"Man"), "TWFu");
        assert_eq!(base64_encode(b"Ma"), "TWE=");
        assert_eq!(base64_decode("TW\nE").unwrap(), b"Ma");
        assert!(base64_decode("T").is_err());
        assert!(base64_decode("TW-E").is_err());
        assert!(base64_decode("TQ==TQ").is_err());
    }

    #[test]
    fn name_uuid_matches_java() {
        // java.util.UUID.nameUUIDFromBytes("abc".getBytes()).toString()
        assert_eq!(name_uuid(b"abc"), "90015098-3cd2-3fb0-9696-3f7d28e17f72");
        assert!(is_canonical_uuid(&name_uuid(b"x")));
        let a = random_uuid();
        assert!(is_canonical_uuid(&a) && a != random_uuid() && &a[14..15] == "4");
        assert!(!is_canonical_uuid("90015098-3CD2-3fb0-9696-3f7d28e17f72"));
        assert!(!is_canonical_uuid("1-1-1-1-1"));
    }
}
