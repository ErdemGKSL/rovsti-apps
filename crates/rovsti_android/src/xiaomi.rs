//! Xiaomi / Redmi / POCO (MIUI and HyperOS) background-execution settings.
//!
//! MIUI and HyperOS kill background apps far more aggressively than AOSP.
//! A dialer, an SMS app with a live WhatsApp socket and an arrival alarm all
//! need to survive in the background, so each app runs a one-time checklist:
//!
//! 1. **Autostart** (Security → Permissions → Autostart). Without it the app
//!    is not restarted for broadcasts and its foreground service may be
//!    killed when swiped from Recents.
//! 2. **Battery saver → No restrictions** (per-app). The AOSP
//!    `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog is honoured on HyperOS
//!    but MIUI 12–14 also needs the MIUI-specific screen.
//! 3. **Show on lock screen** and **Display pop-up windows while running in
//!    background** (MIUI "other permissions"): needed for the incoming-call
//!    screen and the arrival alarm to appear over the lock screen.
//!
//! None of these can be granted programmatically; we can only detect the
//! device and deep-link to the right screen. Detection is pure Rust (system
//! properties); the deep links go through the Kotlin bridge, which tries each
//! known component and falls back to the app's details page.

use crate::{Result, bridge};

/// Which Xiaomi software family the device runs, if any.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum XiaomiOs {
    /// HyperOS (`ro.mi.os.version.name`, e.g. `OS2.0`).
    HyperOs { version: String },
    /// MIUI (`ro.miui.ui.version.name`, e.g. `V140`).
    Miui { version: String },
}

/// Detects MIUI/HyperOS from system properties. `None` on other OEMs and on
/// desktop.
pub fn detect() -> Option<XiaomiOs> {
    let hyper = system_property("ro.mi.os.version.name").filter(|v| !v.is_empty());
    if let Some(version) = hyper {
        return Some(XiaomiOs::HyperOs { version });
    }
    system_property("ro.miui.ui.version.name")
        .filter(|v| !v.is_empty())
        .map(|version| XiaomiOs::Miui { version })
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Setting {
    Autostart,
    BatterySaver,
    /// MIUI "Other permissions": lock screen and background pop-ups.
    OtherPermissions,
}

impl Setting {
    fn bridge_key(self) -> &'static str {
        match self {
            Self::Autostart => "autostart",
            Self::BatterySaver => "battery",
            Self::OtherPermissions => "permissions",
        }
    }
}

/// Deep-links to a Xiaomi settings screen for this app. Returns `false` when
/// no known component resolved and the generic app details page opened.
pub fn open(setting: Setting) -> Result<bool> {
    bridge::call_bool_str("openXiaomiSetting", setting.bridge_key())
}

/// A Xiaomi permission kept in AppOps under a private op code (Kotlin
/// `XiaomiOps`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Op {
    Autostart,
    ShowOnLockScreen,
    /// "Display pop-up windows while running in the background".
    BackgroundPopups,
}

impl Op {
    fn bridge_key(self) -> &'static str {
        match self {
            Self::Autostart => "autostart",
            Self::ShowOnLockScreen => "lock_screen",
            Self::BackgroundPopups => "popups",
        }
    }
}

/// Decodes `RovstiBridge.xiaomiOpState`: 1 granted, 0 denied, anything else
/// (the bridge sends -1) unknown.
pub fn decode_op_state(code: i32) -> Option<bool> {
    match code {
        1 => Some(true),
        0 => Some(false),
        _ => None,
    }
}

/// Reads one Xiaomi permission: `Some(true)` allowed, `Some(false)` denied,
/// `None` when this device gives no usable answer.
pub fn op_allowed(op: Op) -> Result<Option<bool>> {
    bridge::call_int("xiaomiOpState", &[bridge::Arg::Str(op.bridge_key())]).map(decode_op_state)
}

/// AOSP `PowerManager.isIgnoringBatteryOptimizations` for this package.
pub fn is_ignoring_battery_optimizations() -> Result<bool> {
    bridge::call_bool0("isIgnoringBatteryOptimizations")
}

/// AOSP `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog.
pub fn request_ignore_battery_optimizations() -> Result<bool> {
    bridge::call_bool0("requestIgnoreBatteryOptimizations")
}

#[cfg(target_os = "android")]
fn system_property(name: &str) -> Option<String> {
    use std::ffi::{CStr, CString, c_char, c_int};

    // PROP_VALUE_MAX from <sys/system_properties.h>.
    const PROP_VALUE_MAX: usize = 92;
    unsafe extern "C" {
        fn __system_property_get(name: *const c_char, value: *mut c_char) -> c_int;
    }

    let name = CString::new(name).ok()?;
    let mut buf = [0 as c_char; PROP_VALUE_MAX];
    // SAFETY: `name` is NUL-terminated and `buf` is PROP_VALUE_MAX bytes, the
    // documented maximum `__system_property_get` writes (including NUL).
    let len = unsafe { __system_property_get(name.as_ptr(), buf.as_mut_ptr()) };
    if len <= 0 {
        return None;
    }
    // SAFETY: the call wrote a NUL-terminated string into `buf`.
    let value = unsafe { CStr::from_ptr(buf.as_ptr()) };
    Some(value.to_string_lossy().into_owned())
}

#[cfg(not(target_os = "android"))]
fn system_property(_name: &str) -> Option<String> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn op_codes_decode() {
        assert_eq!(decode_op_state(1), Some(true));
        assert_eq!(decode_op_state(0), Some(false));
        assert_eq!(decode_op_state(-1), None);
        assert_eq!(decode_op_state(7), None);
    }

    #[cfg(not(target_os = "android"))]
    #[test]
    fn desktop_is_unsupported() {
        assert!(detect().is_none());
        assert!(matches!(
            op_allowed(Op::Autostart),
            Err(crate::Error::Unsupported)
        ));
    }
}
