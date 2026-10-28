//! Platform probes behind the hidden Diagnostics page (AD-36, NFR-17).
//!
//! Each probe returns one `pass`, `fail` or `unknown` line per check with a
//! short reason. They block (one runs for 20 s), so call [`run`] only from a
//! background thread. Kotlin logs ids and booleans only, under the logcat tag
//! `RovstiSpike`. On desktop everything is [`crate::Error::Unsupported`].

use crate::{
    Result,
    bridge::{self, Arg},
};

/// The probe groups the page lists, in order, as `(probe name, title)`.
pub const GROUPS: [(&str, &str); 12] = [
    ("audio", "Audio"),
    ("calls", "Calls"),
    ("sim", "SIM"),
    ("backup", "Backup"),
    ("blocking", "Blocking"),
    ("reject_text", "Reject text"),
    ("silence", "Silence"),
    ("voicemail", "Voicemail"),
    ("emergency", "Emergency"),
    ("contacts", "Contacts"),
    ("call_log", "Call log"),
    ("system", "TalkBack/system"),
];

/// Probe names that need no group button: the report header and the event ring.
pub const HEADER: &str = "header";
pub const EVENTS: &str = "events";

/// Runs one probe and returns its lines.
pub fn run(probe: &str) -> Result<String> {
    bridge::call_string("diagnostics", &[Arg::Str(probe)])
}

#[cfg(all(test, not(target_os = "android")))]
mod tests {
    use super::*;
    use crate::Error;

    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(run("audio"), Err(Error::Unsupported)));
        assert!(matches!(run(HEADER), Err(Error::Unsupported)));
    }

    #[test]
    fn groups_are_unique() {
        for (i, (name, _)) in GROUPS.iter().enumerate() {
            assert!(GROUPS.iter().skip(i + 1).all(|(n, _)| n != name));
        }
    }
}
