//! The fake-ip pool: an app's A/AAAA query gets an address from a reserved
//! range that stands for the name, so the connection that follows reaches
//! fkbrain with its domain (reverse lookup) and no real resolution happens
//! on the device for names the rules send through a proxy.
//!
//! One slot per name gives both addresses: slot `n` is `v4_base + n` and
//! `v6_base + n`. Slot 0, 1 and the last IPv4 address are never handed out
//! (network, gateway, broadcast). When every slot is taken the oldest
//! allocation is recycled (a cursor walks the slots in order). The table can
//! be persisted to a small text file so fake addresses that apps cached
//! survive a restart of the service.

use std::collections::HashMap;
use std::io::Write;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};
use std::path::Path;

const FIRST_SLOT: u32 = 2;
const STORE_MAGIC: &str = "fkb-fakeip 1";

/// An address range `base/prefix`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Range<T> {
    pub base: T,
    pub prefix: u8,
}

pub fn parse_v4(text: &str) -> Result<Range<Ipv4Addr>, String> {
    let (addr, prefix) = text.split_once('/').ok_or_else(|| format!("fake-ip range {text:?}: not addr/prefix"))?;
    let addr: Ipv4Addr = addr.trim().parse().map_err(|_| format!("fake-ip range {text:?}: not an IPv4 address"))?;
    let prefix: u8 = prefix.trim().parse().map_err(|_| format!("fake-ip range {text:?}: bad prefix"))?;
    if !(8..=29).contains(&prefix) {
        return Err(format!("fake-ip range {text:?}: the prefix must be 8..29"));
    }
    let mask = u32::MAX << (32 - prefix);
    Ok(Range { base: Ipv4Addr::from(u32::from(addr) & mask), prefix })
}

pub fn parse_v6(text: &str) -> Result<Range<Ipv6Addr>, String> {
    let (addr, prefix) = text.split_once('/').ok_or_else(|| format!("fake-ip range {text:?}: not addr/prefix"))?;
    let addr: Ipv6Addr = addr.trim().parse().map_err(|_| format!("fake-ip range {text:?}: not an IPv6 address"))?;
    let prefix: u8 = prefix.trim().parse().map_err(|_| format!("fake-ip range {text:?}: bad prefix"))?;
    if !(8..=120).contains(&prefix) {
        return Err(format!("fake-ip range {text:?}: the prefix must be 8..120"));
    }
    let mask = u128::MAX << (128 - prefix);
    Ok(Range { base: Ipv6Addr::from(u128::from(addr) & mask), prefix })
}

pub struct FakeIpPool {
    v4: Range<Ipv4Addr>,
    v6: Option<Range<Ipv6Addr>>,
    /// Slots `FIRST_SLOT..end` are handed out.
    end: u32,
    by_name: HashMap<Box<str>, u32>,
    by_slot: HashMap<u32, Box<str>>,
    cursor: u32,
    dirty: bool,
}

impl FakeIpPool {
    pub fn new(v4: Range<Ipv4Addr>, v6: Option<Range<Ipv6Addr>>) -> FakeIpPool {
        let mut size = 1u64 << (32 - v4.prefix);
        if let Some(v6) = v6 {
            size = size.min(1u64.checked_shl(128 - v6.prefix as u32).unwrap_or(u64::MAX));
        }
        // The last IPv4 address (broadcast) is left out too.
        let end = (size - 1) as u32;
        FakeIpPool { v4, v6, end, by_name: HashMap::new(), by_slot: HashMap::new(), cursor: FIRST_SLOT, dirty: false }
    }

    pub fn ranges(&self) -> (Range<Ipv4Addr>, Option<Range<Ipv6Addr>>) {
        (self.v4, self.v6)
    }

    /// Slots that can be handed out.
    pub fn capacity(&self) -> usize {
        (self.end - FIRST_SLOT) as usize
    }

    pub fn len(&self) -> usize {
        self.by_name.len()
    }

    pub fn is_empty(&self) -> bool {
        self.by_name.is_empty()
    }

    /// The slot of `name` (lowercase, no trailing dot), allocated when new.
    pub fn allocate(&mut self, name: &str) -> u32 {
        if let Some(&slot) = self.by_name.get(name) {
            return slot;
        }
        let slot = self.cursor;
        self.cursor = if slot + 1 >= self.end { FIRST_SLOT } else { slot + 1 };
        if let Some(old) = self.by_slot.remove(&slot) {
            self.by_name.remove(&old);
        }
        let name: Box<str> = name.into();
        self.by_name.insert(name.clone(), slot);
        self.by_slot.insert(slot, name);
        self.dirty = true;
        slot
    }

    pub fn v4(&self, slot: u32) -> Ipv4Addr {
        Ipv4Addr::from(u32::from(self.v4.base) + slot)
    }

    pub fn v6(&self, slot: u32) -> Option<Ipv6Addr> {
        self.v6.map(|r| Ipv6Addr::from(u128::from(r.base) + slot as u128))
    }

    /// The slot an address falls on when it is inside a fake range.
    pub fn slot_of(&self, ip: IpAddr) -> Option<u32> {
        match ip {
            IpAddr::V4(v4) => {
                let offset = u32::from(v4).wrapping_sub(u32::from(self.v4.base));
                (offset < (1u32 << (32 - self.v4.prefix))).then_some(offset)
            }
            IpAddr::V6(v6) => {
                if let Some(v4) = v6.to_ipv4_mapped() {
                    return self.slot_of(IpAddr::V4(v4));
                }
                let range = self.v6?;
                let offset = u128::from(v6).wrapping_sub(u128::from(range.base));
                let size = 1u128.checked_shl(128 - range.prefix as u32).unwrap_or(u128::MAX);
                (offset < size).then(|| offset.min(u32::MAX as u128) as u32)
            }
        }
    }

    /// `Ok(None)`: not a fake address; `Ok(Some(name))`: the name it stands
    /// for; `Err(())`: inside the range but not allocated (stale).
    pub fn reverse(&self, ip: IpAddr) -> Result<Option<&str>, ()> {
        match self.slot_of(ip) {
            None => Ok(None),
            Some(slot) => self.by_slot.get(&slot).map(|n| Some(&**n)).ok_or(()),
        }
    }

    pub fn is_dirty(&self) -> bool {
        self.dirty
    }

    fn header(&self) -> String {
        let v6 = self.v6.map(|r| format!("{}/{}", r.base, r.prefix)).unwrap_or_else(|| "-".into());
        format!("{STORE_MAGIC} {}/{} {v6}", self.v4.base, self.v4.prefix)
    }

    /// Writes the table (atomically: a temporary file renamed over `path`).
    pub fn save(&mut self, path: &Path) -> std::io::Result<()> {
        let mut text = String::with_capacity(32 + self.by_slot.len() * 24);
        text.push_str(&self.header());
        text.push('\n');
        text.push_str(&format!("cursor {}\n", self.cursor));
        let mut slots: Vec<(&u32, &Box<str>)> = self.by_slot.iter().collect();
        slots.sort_unstable_by_key(|(slot, _)| **slot);
        for (slot, name) in slots {
            text.push_str(&format!("{slot} {name}\n"));
        }
        let tmp = path.with_extension("tmp");
        {
            let mut file = std::fs::File::create(&tmp)?;
            file.write_all(text.as_bytes())?;
            file.sync_all()?;
        }
        std::fs::rename(&tmp, path)?;
        self.dirty = false;
        Ok(())
    }

    /// Reads a table saved for the same ranges; a file for other ranges (or
    /// none) leaves the pool empty. Returns the number of names restored.
    pub fn load(&mut self, path: &Path) -> std::io::Result<usize> {
        let text = match std::fs::read_to_string(path) {
            Ok(text) => text,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(0),
            Err(e) => return Err(e),
        };
        let mut lines = text.lines();
        if lines.next() != Some(self.header().as_str()) {
            return Ok(0);
        }
        for line in lines {
            let Some((key, value)) = line.split_once(' ') else { continue };
            if key == "cursor" {
                if let Ok(cursor) = value.parse::<u32>() {
                    if (FIRST_SLOT..self.end).contains(&cursor) {
                        self.cursor = cursor;
                    }
                }
                continue;
            }
            let Ok(slot) = key.parse::<u32>() else { continue };
            if !(FIRST_SLOT..self.end).contains(&slot) || value.is_empty() || self.by_name.contains_key(value) {
                continue;
            }
            let name: Box<str> = value.into();
            self.by_name.insert(name.clone(), slot);
            if let Some(old) = self.by_slot.insert(slot, name) {
                self.by_name.remove(&old);
            }
        }
        self.dirty = false;
        Ok(self.by_name.len())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pool(v4: &str, v6: Option<&str>) -> FakeIpPool {
        FakeIpPool::new(parse_v4(v4).unwrap(), v6.map(|r| parse_v6(r).unwrap()))
    }

    #[test]
    fn allocation_reverse_and_recycling() {
        let mut p = pool("198.18.0.0/29", Some("fc00::/64"));
        assert_eq!(p.capacity(), 5);
        let a = p.allocate("a.test");
        assert_eq!(p.v4(a), "198.18.0.2".parse::<Ipv4Addr>().unwrap());
        assert_eq!(p.v6(a), Some("fc00::2".parse().unwrap()));
        assert_eq!(p.allocate("a.test"), a, "stable per name");
        assert_eq!(p.reverse("198.18.0.2".parse().unwrap()), Ok(Some("a.test")));
        assert_eq!(p.reverse("fc00::2".parse().unwrap()), Ok(Some("a.test")));
        assert_eq!(p.reverse("::ffff:198.18.0.2".parse().unwrap()), Ok(Some("a.test")));
        assert_eq!(p.reverse("198.18.0.6".parse().unwrap()), Err(()), "in range, never allocated");
        assert_eq!(p.reverse("198.19.0.2".parse().unwrap()), Ok(None));
        assert_eq!(p.reverse("8.8.8.8".parse().unwrap()), Ok(None));
        for name in ["b.test", "c.test", "d.test", "e.test"] {
            p.allocate(name);
        }
        assert_eq!(p.len(), 5);
        // Full: the oldest slot (a.test's) is recycled.
        let f = p.allocate("f.test");
        assert_eq!(f, a);
        assert_eq!(p.reverse(IpAddr::V4(p.v4(f))), Ok(Some("f.test")));
        assert_eq!(p.len(), 5);
        assert_ne!(p.allocate("a.test"), f, "a.test comes back on the next slot");
        let mut no_v6 = pool("10.0.0.0/24", None);
        let s = no_v6.allocate("x.test");
        assert_eq!(no_v6.v6(s), None);
        assert_eq!(no_v6.reverse("fc00::2".parse().unwrap()), Ok(None));
        assert!(parse_v4("198.18.0.0/30").is_err());
        assert!(parse_v4("nope/15").is_err());
        assert_eq!(parse_v4("198.18.7.9/15").unwrap().base, "198.18.0.0".parse::<Ipv4Addr>().unwrap());
    }

    #[test]
    fn persistence() {
        let dir = std::env::temp_dir().join(format!("fkb-fakeip-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("fakeip.txt");
        let mut p = pool("198.18.0.0/24", Some("fc00::/64"));
        let a = p.allocate("a.test");
        let b = p.allocate("b.test");
        assert!(p.is_dirty());
        p.save(&path).unwrap();
        assert!(!p.is_dirty());
        let mut restored = pool("198.18.0.0/24", Some("fc00::/64"));
        assert_eq!(restored.load(&path).unwrap(), 2);
        assert_eq!(restored.reverse(IpAddr::V4(restored.v4(b))), Ok(Some("b.test")));
        assert_eq!(restored.allocate("a.test"), a);
        assert_eq!(restored.allocate("c.test"), b + 1, "the cursor survives");
        let mut other = pool("198.18.1.0/24", Some("fc00::/64"));
        assert_eq!(other.load(&path).unwrap(), 0, "another range ignores the file");
        assert_eq!(pool("198.18.0.0/24", None).load(&dir.join("missing")).unwrap(), 0);
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
