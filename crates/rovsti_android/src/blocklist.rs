//! The system blocklist (`BlockedNumberContract`, AD-27). Kotlin side:
//! `Blocklist`, `CallLogQuery.blockedJson` (stories 17.1, 17.2).
//!
//! All of them block on a content-provider call: run them on a background
//! executor. On desktop they return [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// Blocklist rows as an envelope (`rovsti_core::wire::BlocklistResponse`).
pub fn pull() -> Result<String> {
    bridge::call_string("pullBlocklist", &[])
}

/// Whether this user may touch the system blocklist at all
/// (`BlockedNumberContract.canCurrentUserBlockNumbers`).
pub fn can_block() -> Result<bool> {
    bridge::call_bool("canBlockNumbers", &[])
}

/// Whether `number` is on the list now (`BlockedNumberContract.isBlocked`).
pub fn is_blocked(number: &str) -> Result<bool> {
    bridge::call_bool("isNumberBlocked", &[Arg::Str(number)])
}

/// The newest `limit` blocked calls (call-log type 6) as a call-log envelope
/// whose rows carry `block_reason`.
pub fn pull_calls(limit: usize) -> Result<String> {
    let limit = i32::try_from(limit).unwrap_or(i32::MAX);
    bridge::call_string("pullBlockedCalls", &[Arg::Int(limit)])
}

/// Adds `number` to the blocklist. `false` when Android refused.
/// Callers run `rovsti_core::blocklist::can_block` first.
pub fn block(number: &str) -> Result<bool> {
    bridge::call_bool("blockNumber", &[Arg::Str(number)])
}

/// Removes `number` from the blocklist. `false` when Android refused.
pub fn unblock(number: &str) -> Result<bool> {
    bridge::call_bool("unblockNumber", &[Arg::Str(number)])
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(pull(), Err(Error::Unsupported)));
        assert!(matches!(block("0555"), Err(Error::Unsupported)));
        assert!(matches!(unblock("0555"), Err(Error::Unsupported)));
        assert!(matches!(can_block(), Err(Error::Unsupported)));
        assert!(matches!(is_blocked("0555"), Err(Error::Unsupported)));
        assert!(matches!(pull_calls(200), Err(Error::Unsupported)));
    }
}
