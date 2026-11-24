//! The groups' control path for the UI (replaces the Clash API): list the
//! groups with their members and delays, select a member of a select
//! group, run a delay test now. Called on user action, never per
//! connection; the list is a documented JSON document in a caller buffer
//! (docs/architecture/brain-groups.md), the rest takes and returns strings.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::Instant;

use super::{ControlError, Force};
use crate::abi::*;
use crate::{global, write_buf};

fn read_str(s: &FkbStr) -> Result<&str, ControlError> {
    if s.ptr.is_null() && s.len != 0 {
        return Err((FKB_E_INVALID, "NULL string".into()));
    }
    if s.len == 0 {
        return Ok("");
    }
    // SAFETY: the caller keeps `len` bytes at `ptr` alive for the call.
    std::str::from_utf8(unsafe { s.as_bytes() }).map_err(|_| (FKB_E_INVALID, "not UTF-8".into()))
}

fn write_error(err: *mut FkbBuf, message: &str) {
    if err.is_null() {
        return;
    }
    // SAFETY: the caller passes a writable buffer of `cap` bytes at `ptr` (or a NULL ptr with cap 0).
    unsafe {
        let buf = &mut *err;
        let n = message.len().min(buf.cap);
        if n > 0 && !buf.ptr.is_null() {
            std::ptr::copy_nonoverlapping(message.as_ptr(), buf.ptr, n);
        }
        buf.len = n;
    }
}

fn finish(outcome: std::thread::Result<Result<(), ControlError>>, err: *mut FkbBuf) -> i32 {
    match outcome {
        Ok(Ok(())) => FKB_OK,
        Ok(Err((code, message))) => {
            write_error(err, &message);
            code
        }
        Err(_) => {
            write_error(err, "panic in the brain");
            FKB_E_PANIC
        }
    }
}

/// Writes the groups as JSON into `out` (`FKB_E_BUFFER` with `len` = the
/// size needed when it does not fit). Without a running profile the list is empty.
///
/// # Safety
/// `out` points to a writable buffer.
#[no_mangle]
pub unsafe extern "C" fn fkb_brain_groups(out: *mut FkbBuf) -> i32 {
    if out.is_null() {
        return FKB_E_INVALID;
    }
    match catch_unwind(AssertUnwindSafe(|| global().groups().view(Instant::now()).to_string())) {
        // SAFETY: checked non-null; the caller owns the buffer.
        Ok(text) => unsafe { write_buf(&mut *out, text.as_bytes()) },
        Err(_) => FKB_E_PANIC,
    }
}

/// Selects `member` in the select group `group`. `FKB_E_NOT_FOUND` for an
/// unknown group or member, `FKB_E_INVALID` for an automatic group
/// (url-test, fallback, load-balance choose by themselves). The next
/// connection uses the new member; nothing restarts.
///
/// # Safety
/// The strings are readable for the call; `err` is NULL or a writable buffer.
#[no_mangle]
pub unsafe extern "C" fn fkb_brain_group_select(group: FkbStr, member: FkbStr, err: *mut FkbBuf) -> i32 {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        let group = read_str(&group)?;
        let member = read_str(&member)?;
        global().select_group(group, member)
    }));
    finish(outcome, err)
}

/// Tests the delay of a group's members now (an empty name: every group)
/// and lets url-test, fallback and load-balance act on the results.
/// Blocks until the round is done (at most about the groups' timeouts).
///
/// # Safety
/// As [`fkb_brain_group_select`].
#[no_mangle]
pub unsafe extern "C" fn fkb_brain_group_test(group: FkbStr, err: *mut FkbBuf) -> i32 {
    let outcome = catch_unwind(AssertUnwindSafe(|| {
        let name = read_str(&group)?;
        let brain = global();
        let force = if name.is_empty() {
            Force::All
        } else {
            Force::One(brain.groups().set().group(name).ok_or_else(|| (FKB_E_NOT_FOUND, format!("unknown group {name:?}")))?)
        };
        brain.test_groups(force).map(|_| ()).map_err(|e| (e.code, e.message))
    }));
    finish(outcome, err)
}
