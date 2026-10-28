//! The perf-build trigger (story 12.2): `adb shell setprop debug.rovsti.perf
//! <scenario>:<n>` starts a scripted scenario in a build made with the
//! `perf` feature. Only the perf runner calls this; release builds never do.

use crate::{Error, Result};

/// The system property the runner polls.
pub const PROPERTY: &str = "debug.rovsti.perf";

/// Current value of [`PROPERTY`] (empty when unset).
#[cfg(target_os = "android")]
pub fn trigger() -> Result<String> {
    use std::ffi::{CString, c_char, c_int};

    unsafe extern "C" {
        fn __system_property_get(name: *const c_char, value: *mut c_char) -> c_int;
    }
    // PROP_VALUE_MAX is 92 including the terminator.
    let mut value = [0 as c_char; 96];
    let name = CString::new(PROPERTY).map_err(|_| Error::Unsupported)?;
    // SAFETY: `name` is NUL-terminated and `value` holds PROP_VALUE_MAX bytes,
    // which is the most the call writes (terminator included).
    let len = unsafe { __system_property_get(name.as_ptr(), value.as_mut_ptr()) };
    let len = usize::try_from(len).unwrap_or(0).min(value.len() - 1);
    let bytes: Vec<u8> = value[..len].iter().map(|&c| c as u8).collect();
    Ok(String::from_utf8_lossy(&bytes).into_owned())
}

#[cfg(not(target_os = "android"))]
pub fn trigger() -> Result<String> {
    Err(Error::Unsupported)
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(trigger(), Err(Error::Unsupported)));
    }
}
