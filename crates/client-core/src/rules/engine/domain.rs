//! Domain normalization and the compiled domain index: exact names, suffixes
//! (a hash per label boundary, i.e. a flattened label trie), keywords
//! (Aho-Corasick), regexes (one `RegexSet`) and wildcards. Each entry carries
//! a value; a lookup returns the smallest value among the entries that match
//! (the earliest rule), so the same index serves a whole program and a single
//! set (every value 0).

use std::borrow::Cow;
use std::collections::HashMap;
use std::hash::{BuildHasherDefault, Hasher};

use aho_corasick::AhoCorasick;
use regex::{RegexSet, RegexSetBuilder};

/// FxHash (rustc's hasher): the keys come from the configuration, and a
/// lookup hashes a few short labels per decide.
#[derive(Default, Clone, Copy)]
pub struct FxHasher {
    hash: u64,
}

const SEED: u64 = 0x51_7c_c1_b7_27_22_0a_95;

impl FxHasher {
    #[inline]
    fn add(&mut self, word: u64) {
        self.hash = (self.hash.rotate_left(5) ^ word).wrapping_mul(SEED);
    }
}

impl Hasher for FxHasher {
    #[inline]
    fn write(&mut self, bytes: &[u8]) {
        let mut chunks = bytes.chunks_exact(8);
        for chunk in &mut chunks {
            let mut word = [0u8; 8];
            word.copy_from_slice(chunk);
            self.add(u64::from_le_bytes(word));
        }
        let rest = chunks.remainder();
        if !rest.is_empty() {
            let mut word = [0u8; 8];
            word[..rest.len()].copy_from_slice(rest);
            self.add(u64::from_le_bytes(word) ^ ((rest.len() as u64) << 56));
        }
    }

    #[inline]
    fn write_u8(&mut self, i: u8) {
        self.add(u64::from(i));
    }

    #[inline]
    fn write_usize(&mut self, i: usize) {
        self.add(i as u64);
    }

    #[inline]
    fn finish(&self) -> u64 {
        self.hash
    }
}

pub type FxMap<K, V> = HashMap<K, V, BuildHasherDefault<FxHasher>>;

/// A domain as rules compare it: no trailing dot, ASCII lowercase, an
/// internationalized name in its ASCII (punycode) form. `None` for an empty
/// name or one IDNA rejects.
pub fn normalize(input: &str) -> Option<Cow<'_, str>> {
    let trimmed = input.trim().trim_end_matches('.');
    if trimmed.is_empty() {
        return None;
    }
    if trimmed.is_ascii() {
        if trimmed.bytes().any(|b| b.is_ascii_uppercase()) {
            return Some(Cow::Owned(trimmed.to_ascii_lowercase()));
        }
        return Some(Cow::Borrowed(trimmed));
    }
    idna::domain_to_ascii(trimmed).ok().filter(|d| !d.is_empty()).map(Cow::Owned)
}

/// A rule value normalized, or an error naming it.
pub fn normalize_value(input: &str) -> Result<String, String> {
    normalize(input).map(Cow::into_owned).ok_or_else(|| format!("invalid domain {input:?}"))
}

/// mihomo's wildcard match (`component/wildcard`): `*` any run, `?` one byte.
pub fn wildcard_match(pattern: &[u8], text: &[u8]) -> bool {
    let (mut p, mut t) = (0usize, 0usize);
    let mut star: Option<(usize, usize)> = None;
    while t < text.len() {
        if p < pattern.len() && (pattern[p] == b'?' || pattern[p] == text[t]) {
            p += 1;
            t += 1;
        } else if p < pattern.len() && pattern[p] == b'*' {
            star = Some((p, t));
            p += 1;
        } else if let Some((sp, st)) = star {
            p = sp + 1;
            t = st + 1;
            star = Some((sp, st + 1));
        } else {
            return false;
        }
    }
    while p < pattern.len() && pattern[p] == b'*' {
        p += 1;
    }
    p == pattern.len()
}

/// Turns a mihomo domain-set pattern with `*` labels (`*.example.com`, one
/// label each) into a regex.
pub fn label_wildcard_regex(pattern: &str) -> String {
    let labels: Vec<String> = pattern.split('.').map(|l| if l == "*" { "[^.]+".to_string() } else { regex::escape(l) }).collect();
    format!("^{}$", labels.join(r"\."))
}

fn keep_min<K: std::hash::Hash + Eq>(map: &mut FxMap<K, u32>, key: K, value: u32) {
    map.entry(key).and_modify(|v| *v = (*v).min(value)).or_insert(value);
}

#[derive(Default)]
pub struct DomainIndexBuilder {
    full: FxMap<Box<str>, u32>,
    suffix: FxMap<Box<str>, u32>,
    subdomain: FxMap<Box<str>, u32>,
    keywords: FxMap<String, u32>,
    regexes: Vec<(String, u32)>,
    wildcards: Vec<(Box<[u8]>, u32)>,
}

impl DomainIndexBuilder {
    pub fn full(&mut self, name: &str, value: u32) {
        keep_min(&mut self.full, name.into(), value);
    }

    pub fn suffix(&mut self, name: &str, value: u32) {
        keep_min(&mut self.suffix, name.into(), value);
    }

    pub fn subdomain(&mut self, name: &str, value: u32) {
        keep_min(&mut self.subdomain, name.into(), value);
    }

    pub fn keyword(&mut self, word: &str, value: u32) {
        keep_min(&mut self.keywords, word.to_ascii_lowercase(), value);
    }

    pub fn regex(&mut self, pattern: &str, value: u32) {
        self.regexes.push((pattern.to_string(), value));
    }

    pub fn wildcard(&mut self, pattern: &str, value: u32) {
        self.wildcards.push((pattern.to_ascii_lowercase().into_bytes().into_boxed_slice(), value));
    }

    pub fn is_empty(&self) -> bool {
        self.full.is_empty()
            && self.suffix.is_empty()
            && self.subdomain.is_empty()
            && self.keywords.is_empty()
            && self.regexes.is_empty()
            && self.wildcards.is_empty()
    }

    pub fn len(&self) -> usize {
        self.full.len() + self.suffix.len() + self.subdomain.len() + self.keywords.len() + self.regexes.len() + self.wildcards.len()
    }

    pub fn build(self) -> Result<DomainIndex, String> {
        let keywords = if self.keywords.is_empty() {
            None
        } else {
            let (words, values): (Vec<String>, Vec<u32>) = self.keywords.into_iter().unzip();
            let ac = AhoCorasick::new(&words).map_err(|e| format!("keyword automaton: {e}"))?;
            Some((ac, values))
        };
        let regexes = if self.regexes.is_empty() {
            None
        } else {
            let (patterns, values): (Vec<String>, Vec<u32>) = self.regexes.into_iter().unzip();
            let set = RegexSetBuilder::new(&patterns)
                .case_insensitive(true)
                .size_limit(64 << 20)
                .dfa_size_limit(16 << 20)
                .build()
                .map_err(|e| format!("domain regex: {e}"))?;
            Some((set, values))
        };
        let mut full = self.full;
        let mut suffix = self.suffix;
        let mut subdomain = self.subdomain;
        full.shrink_to_fit();
        suffix.shrink_to_fit();
        subdomain.shrink_to_fit();
        Ok(DomainIndex { full, suffix, subdomain, keywords, regexes, wildcards: self.wildcards })
    }
}

pub struct DomainIndex {
    full: FxMap<Box<str>, u32>,
    suffix: FxMap<Box<str>, u32>,
    subdomain: FxMap<Box<str>, u32>,
    keywords: Option<(AhoCorasick, Vec<u32>)>,
    regexes: Option<(RegexSet, Vec<u32>)>,
    wildcards: Vec<(Box<[u8]>, u32)>,
}

impl DomainIndex {
    pub fn empty() -> DomainIndex {
        DomainIndexBuilder::default().build().unwrap_or_else(|_| unreachable!())
    }

    pub fn is_empty(&self) -> bool {
        self.full.is_empty()
            && self.suffix.is_empty()
            && self.subdomain.is_empty()
            && self.keywords.is_none()
            && self.regexes.is_none()
            && self.wildcards.is_empty()
    }

    /// The smallest value among the entries matching `domain` (normalized).
    pub fn lookup(&self, domain: &str) -> Option<u32> {
        let mut best = u32::MAX;
        let mut take = |v: Option<&u32>| {
            if let Some(&v) = v {
                if v < best {
                    best = v;
                }
            }
        };
        if !self.full.is_empty() {
            take(self.full.get(domain));
        }
        let suffixes = !self.suffix.is_empty();
        let subdomains = !self.subdomain.is_empty();
        if suffixes {
            take(self.suffix.get(domain));
        }
        if suffixes || subdomains {
            for (i, b) in domain.bytes().enumerate() {
                if b == b'.' {
                    let rest = &domain[i + 1..];
                    if suffixes {
                        take(self.suffix.get(rest));
                    }
                    if subdomains {
                        take(self.subdomain.get(rest));
                    }
                }
            }
        }
        if let Some((ac, values)) = &self.keywords {
            for m in ac.find_overlapping_iter(domain) {
                best = best.min(values[m.pattern().as_usize()]);
            }
        }
        if let Some((set, values)) = &self.regexes {
            // Only regexes that can still lower the result are worth running.
            if values.iter().any(|&v| v < best) {
                for i in set.matches(domain).iter() {
                    best = best.min(values[i]);
                }
            }
        }
        for (pattern, value) in &self.wildcards {
            if *value < best && wildcard_match(pattern, domain.as_bytes()) {
                best = *value;
            }
        }
        (best != u32::MAX).then_some(best)
    }

    pub fn contains(&self, domain: &str) -> bool {
        self.lookup(domain).is_some()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_case_dots_and_idn() {
        assert_eq!(normalize("WWW.Example.COM.").unwrap(), "www.example.com");
        assert!(matches!(normalize("example.com").unwrap(), Cow::Borrowed(_)));
        assert_eq!(normalize("例子.测试").unwrap(), "xn--fsqu00a.xn--0zwm56d");
        assert_eq!(normalize("Bücher.example").unwrap(), "xn--bcher-kva.example");
        assert!(normalize("").is_none());
        assert!(normalize(" . ").is_none());
        assert!(normalize_value("").is_err());
    }

    #[test]
    fn wildcards_follow_mihomo() {
        assert!(wildcard_match(b"*.example.com", b"a.b.example.com"));
        assert!(wildcard_match(b"ex?mple.*", b"example.org"));
        assert!(!wildcard_match(b"*.example.com", b"example.com"));
        assert!(wildcard_match(b"*", b""));
        assert!(!wildcard_match(b"a?", b"a"));
        let re = regex::Regex::new(&label_wildcard_regex("*.example.com")).unwrap();
        assert!(re.is_match("a.example.com"));
        assert!(!re.is_match("a.b.example.com"));
        assert!(!re.is_match("example.com"));
    }

    #[test]
    fn index_returns_the_smallest_matching_value() {
        let mut b = DomainIndexBuilder::default();
        b.full("exact.example.com", 5);
        b.suffix("example.com", 7);
        b.suffix("example.com", 9);
        b.subdomain("sub.test", 3);
        b.keyword("ADS", 8);
        b.regex(r"^tracker\d+\.", 2);
        b.wildcard("w?ld.*", 6);
        assert_eq!(b.len(), 6);
        let idx = b.build().unwrap();
        assert_eq!(idx.lookup("exact.example.com"), Some(5));
        assert_eq!(idx.lookup("example.com"), Some(7));
        assert_eq!(idx.lookup("a.b.example.com"), Some(7));
        assert_eq!(idx.lookup("notexample.com"), None);
        assert_eq!(idx.lookup("sub.test"), None);
        assert_eq!(idx.lookup("x.sub.test"), Some(3));
        assert_eq!(idx.lookup("myads.example.com"), Some(7));
        assert_eq!(idx.lookup("myads.net"), Some(8));
        assert_eq!(idx.lookup("tracker12.example.com"), Some(2));
        assert_eq!(idx.lookup("wild.org"), Some(6));
        assert_eq!(idx.lookup("com"), None);
        assert!(DomainIndex::empty().is_empty());
        assert_eq!(DomainIndex::empty().lookup("a.com"), None);
    }

    #[test]
    fn bad_regex_is_an_error() {
        let mut b = DomainIndexBuilder::default();
        b.regex("(", 0);
        assert!(b.build().is_err());
    }
}
