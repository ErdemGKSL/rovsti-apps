//! Default-app roles (`android.app.role.RoleManager`, API 29+).
//!
//! Rovsti Phone must hold `ROLE_DIALER` to get an `InCallService` and the
//! call log; Rovsti Messages must hold `ROLE_SMS` to write to the SMS
//! provider and receive `SMS_DELIVER`. Without the role both apps fall back
//! to read-only mode.

use crate::{Result, bridge};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    Dialer,
    Sms,
}

impl Role {
    /// The `RoleManager.ROLE_*` constant value.
    pub fn as_android(self) -> &'static str {
        match self {
            Self::Dialer => "android.app.role.DIALER",
            Self::Sms => "android.app.role.SMS",
        }
    }
}

pub fn is_held(role: Role) -> Result<bool> {
    bridge::call_bool_str("isRoleHeld", role.as_android())
}

/// Shows the system "Set as default" dialog. Returns `false` when the role is
/// unavailable on this device (some Xiaomi builds hide it behind
/// Settings → Apps → Default apps; open that instead, see [`crate::xiaomi`]).
pub fn request(role: Role) -> Result<bool> {
    bridge::call_bool_str("requestRole", role.as_android())
}
