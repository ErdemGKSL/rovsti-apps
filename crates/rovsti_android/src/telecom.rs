//! Calls through Android's Telecom stack.

use crate::{Result, bridge};

/// Places a call with `TelecomManager.placeCall` (needs `CALL_PHONE`; as the
/// default dialer Rovsti Phone also gets the in-call UI). Returns `false` if
/// the permission is missing and the system dial pad was opened instead.
pub fn place_call(number: &str) -> Result<bool> {
    bridge::call_bool_str("placeCall", number)
}
