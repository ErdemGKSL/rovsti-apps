//! The system blocklist (`BlockedNumberContract`, AD-27). Facade only: the
//! Kotlin bodies arrive with the blocking stories (18.x); the desktop contract
//! is fixed now (NFR-7).
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
    }
}
