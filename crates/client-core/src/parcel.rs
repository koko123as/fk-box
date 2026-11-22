//! The Android `Parcel` bytes the app's Room `profiles.typed` column holds
//! (`TypedProfile.writeToParcel`, marshalled). Read for the one-time import
//! only; the store keeps the fields as columns.
//!
//! Parcel layout (little-endian, as on every Android ABI): `writeInt` is 4
//! bytes, `writeLong` 8 bytes, `writeString` a 4-byte length in UTF-16 units
//! (-1 for null) followed by the UTF-16 units, a 0 terminator and padding to
//! 4 bytes. Strings written by `writeString8` (UTF-8 bytes) are accepted too.

use crate::storage::{Profile, CORES, CORE_AUTO, DEFAULT_INTERVAL};

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
    utf8: bool,
}

impl Reader<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8], String> {
        let end = self.pos.checked_add(n).filter(|end| *end <= self.data.len()).ok_or("truncated parcel")?;
        let bytes = &self.data[self.pos..end];
        self.pos = end;
        Ok(bytes)
    }

    fn int(&mut self) -> Result<i32, String> {
        Ok(i32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }

    fn long(&mut self) -> Result<i64, String> {
        Ok(i64::from_le_bytes(self.take(8)?.try_into().unwrap()))
    }

    fn pad(&mut self, written: usize) -> Result<(), String> {
        let padded = (written + 3) & !3;
        self.take(padded - written).map(|_| ())
    }

    fn string(&mut self) -> Result<Option<String>, String> {
        let len = self.int()?;
        if len == -1 {
            return Ok(None);
        }
        let len = usize::try_from(len).map_err(|_| "invalid string length")?;
        if self.utf8 {
            let bytes = self.take(len + 1)?.to_vec();
            self.pad(len + 1)?;
            if bytes[len] != 0 {
                return Err("unterminated string".into());
            }
            return String::from_utf8(bytes[..len].to_vec()).map(Some).map_err(|_| "invalid UTF-8 string".into());
        }
        let bytes = self.take((len + 1) * 2)?.to_vec();
        self.pad((len + 1) * 2)?;
        let units: Vec<u16> = bytes.chunks_exact(2).map(|c| u16::from_le_bytes([c[0], c[1]])).collect();
        if units[len] != 0 {
            return Err("unterminated string".into());
        }
        String::from_utf16(&units[..len]).map(Some).map_err(|_| "invalid UTF-16 string".into())
    }
}

/// Reads a `typed` blob onto `profile` (every TypedProfile parcel version;
/// v1/v2 values are kept as they were written), as `TypedProfile.readFields`.
pub fn read_typed_profile(data: &[u8], profile: &mut Profile) -> Result<(), String> {
    read_with(data, profile, false).or_else(|utf16_error| read_with(data, profile, true).map_err(|_| utf16_error))
}

fn read_with(data: &[u8], profile: &mut Profile, utf8: bool) -> Result<(), String> {
    let mut r = Reader { data, pos: 0, utf8 };
    let mut p = profile.clone();
    let version = r.int()?;
    p.path = r.string()?.unwrap_or_default();
    // Type.valueOf: an unknown ordinal reads as Local.
    p.kind = if r.int()? == 1 { 1 } else { 0 };
    p.remote_url = r.string()?.unwrap_or_default();
    p.auto_update = r.int()? == 1;
    p.last_updated = r.long()?;
    p.auto_update_interval = DEFAULT_INTERVAL;
    if version >= 1 {
        p.auto_update_interval = r.int()?;
    }
    p.core = CORE_AUTO.into();
    if version >= 2 {
        p.core = r.string()?.filter(|c| CORES.contains(&c.as_str())).unwrap_or_else(|| CORE_AUTO.into());
    }
    if version >= 3 {
        p.upload = r.long()?.max(0);
        p.download = r.long()?.max(0);
        p.total = r.long()?.max(0);
        p.expire = r.long()?.max(0);
        p.web_page_url = r.string()?.unwrap_or_default();
        p.interval_user_set = r.int()? == 1;
    } else {
        // Before provider hints existed every non-default interval was the user's choice.
        p.interval_user_set = p.auto_update_interval != DEFAULT_INTERVAL;
    }
    *profile = p;
    Ok(())
}

/// Writes `profile` the way `TypedProfile.writeFields` does (parcel
/// `version`, 0–3): test fixtures for the import.
#[cfg(test)]
pub fn write_typed_profile(profile: &Profile, version: i32) -> Vec<u8> {
    let mut out = Vec::new();
    let int = |out: &mut Vec<u8>, v: i32| out.extend_from_slice(&v.to_le_bytes());
    let long = |out: &mut Vec<u8>, v: i64| out.extend_from_slice(&v.to_le_bytes());
    let string = |out: &mut Vec<u8>, s: &str| {
        let units: Vec<u16> = s.encode_utf16().collect();
        out.extend_from_slice(&(units.len() as i32).to_le_bytes());
        for u in &units {
            out.extend_from_slice(&u.to_le_bytes());
        }
        out.extend_from_slice(&[0, 0]);
        let written = (units.len() + 1) * 2;
        out.resize(out.len() + (((written + 3) & !3) - written), 0);
    };
    int(&mut out, version);
    string(&mut out, &profile.path);
    int(&mut out, profile.kind as i32);
    string(&mut out, &profile.remote_url);
    int(&mut out, i32::from(profile.auto_update));
    long(&mut out, profile.last_updated);
    if version >= 1 {
        int(&mut out, profile.auto_update_interval);
    }
    if version >= 2 {
        string(&mut out, &profile.core);
    }
    if version >= 3 {
        long(&mut out, profile.upload);
        long(&mut out, profile.download);
        long(&mut out, profile.total);
        long(&mut out, profile.expire);
        string(&mut out, &profile.web_page_url);
        int(&mut out, i32::from(profile.interval_user_set));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Profile {
        Profile {
            path: "/data/user/0/io.fkbox.app/files/configs/a.json".into(),
            kind: 1,
            remote_url: "https://example.com/sub?token=ä".into(),
            auto_update: true,
            last_updated: 1_700_000_000_123,
            auto_update_interval: 120,
            core: "mihomo".into(),
            upload: 1,
            download: 2,
            total: 3,
            expire: 4,
            web_page_url: "https://example.com".into(),
            interval_user_set: true,
            ..Default::default()
        }
    }

    #[test]
    fn version_3_round_trips() {
        let mut read = Profile::default();
        read_typed_profile(&write_typed_profile(&sample(), 3), &mut read).unwrap();
        assert_eq!(read, sample());
    }

    #[test]
    fn older_versions_take_defaults() {
        let mut read = Profile::default();
        read_typed_profile(&write_typed_profile(&sample(), 0), &mut read).unwrap();
        assert_eq!((read.auto_update_interval, read.core.as_str(), read.interval_user_set, read.total), (60, "auto", false, 0));
        let mut read = Profile::default();
        read_typed_profile(&write_typed_profile(&sample(), 1), &mut read).unwrap();
        // v1/v2: a non-default interval was the user's choice.
        assert_eq!((read.auto_update_interval, read.core.as_str(), read.interval_user_set), (120, "auto", true));
        let mut read = Profile::default();
        read_typed_profile(&write_typed_profile(&sample(), 2), &mut read).unwrap();
        assert_eq!((read.core.as_str(), read.web_page_url.as_str()), ("mihomo", ""));
    }

    #[test]
    fn unknown_core_and_negative_counts_are_normalized() {
        let mut p = sample();
        p.core = "v2ray".into();
        p.upload = -5;
        let mut read = Profile::default();
        read_typed_profile(&write_typed_profile(&p, 3), &mut read).unwrap();
        assert_eq!((read.core.as_str(), read.upload), ("auto", 0));
    }

    #[test]
    fn utf8_strings_are_accepted() {
        let mut out = Vec::new();
        out.extend_from_slice(&0i32.to_le_bytes());
        let path = b"/x.json";
        out.extend_from_slice(&(path.len() as i32).to_le_bytes());
        out.extend_from_slice(path);
        out.push(0);
        out.resize(out.len() + ((path.len() + 1 + 3) & !3) - (path.len() + 1), 0);
        out.extend_from_slice(&0i32.to_le_bytes());
        out.extend_from_slice(&(-1i32).to_le_bytes());
        out.extend_from_slice(&0i32.to_le_bytes());
        out.extend_from_slice(&5i64.to_le_bytes());
        let mut read = Profile::default();
        read_typed_profile(&out, &mut read).unwrap();
        assert_eq!((read.path.as_str(), read.remote_url.as_str(), read.last_updated), ("/x.json", "", 5));
    }

    #[test]
    fn truncated_data_fails() {
        let data = write_typed_profile(&sample(), 3);
        let mut read = Profile::default();
        assert!(read_typed_profile(&data[..data.len() - 6], &mut read).is_err());
        assert_eq!(read, Profile::default());
    }
}
