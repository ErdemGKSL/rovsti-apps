//! Local marks on SMS threads: pinned, archived and marked unread. SMS has no
//! provider-side equivalents, so these live on the device.

use std::collections::BTreeSet;

use serde::{Deserialize, Serialize};

/// How many SMS conversations can be pinned locally.
pub const MAX_LOCAL_PINS: usize = 5;

/// Pill text when the pin cap is hit.
pub const TOO_MANY_PINS: &str = "You can pin up to 5 SMS conversations";

/// Why a pin was refused.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PinError {
    /// [`MAX_LOCAL_PINS`] threads are already pinned.
    TooMany,
}

/// Local pinned / archived / unread marks, keyed by SMS thread id.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SmsMarks {
    /// Pinned threads.
    #[serde(default)]
    pub pinned: BTreeSet<i64>,
    /// Archived threads.
    #[serde(default)]
    pub archived: BTreeSet<i64>,
    /// Threads marked unread by hand.
    #[serde(default)]
    pub unread: BTreeSet<i64>,
}

impl SmsMarks {
    /// Whether the thread is pinned.
    pub fn is_pinned(&self, thread: i64) -> bool {
        self.pinned.contains(&thread)
    }

    /// Whether the thread is archived.
    pub fn is_archived(&self, thread: i64) -> bool {
        self.archived.contains(&thread)
    }

    /// Whether the thread is marked unread.
    pub fn is_marked_unread(&self, thread: i64) -> bool {
        self.unread.contains(&thread)
    }

    /// Pin or unpin. Pinning also unarchives. Returns whether anything changed;
    /// pinning a new thread past the cap is refused.
    pub fn set_pinned(&mut self, thread: i64, pinned: bool) -> Result<bool, PinError> {
        if !pinned {
            return Ok(self.pinned.remove(&thread));
        }
        if self.pinned.contains(&thread) {
            return Ok(self.archived.remove(&thread));
        }
        if self.pinned.len() >= MAX_LOCAL_PINS {
            return Err(PinError::TooMany);
        }
        self.archived.remove(&thread);
        self.pinned.insert(thread);
        Ok(true)
    }

    /// Archive or unarchive. Archiving also unpins. Returns whether anything changed.
    pub fn set_archived(&mut self, thread: i64, archived: bool) -> bool {
        if archived {
            let unpinned = self.pinned.remove(&thread);
            self.archived.insert(thread) || unpinned
        } else {
            self.archived.remove(&thread)
        }
    }

    /// Mark or unmark unread. Returns whether anything changed.
    pub fn set_unread(&mut self, thread: i64, unread: bool) -> bool {
        if unread {
            self.unread.insert(thread)
        } else {
            self.unread.remove(&thread)
        }
    }

    /// Clear the unread mark (on open, Mark read). Returns whether it was set.
    pub fn clear_unread(&mut self, thread: i64) -> bool {
        self.unread.remove(&thread)
    }

    /// Drop every mark of a deleted thread. Returns whether anything was removed.
    pub fn forget(&mut self, thread: i64) -> bool {
        let a = self.pinned.remove(&thread);
        let b = self.archived.remove(&thread);
        let c = self.unread.remove(&thread);
        a || b || c
    }

    /// Move every mark from `old` to `new` when the provider changes the thread id.
    pub fn rekey(&mut self, old: i64, new: i64) -> bool {
        if old == new {
            return false;
        }
        let mut changed = false;
        for set in [&mut self.pinned, &mut self.archived, &mut self.unread] {
            if set.remove(&old) {
                set.insert(new);
                changed = true;
            }
        }
        changed
    }
}

/// Pill text after pinning or unpinning.
pub fn pin_notice(pinned: bool) -> &'static str {
    if pinned { "Pinned" } else { "Unpinned" }
}

/// Pill text after archiving or unarchiving.
pub fn archive_notice(archived: bool) -> &'static str {
    if archived { "Archived" } else { "Unarchived" }
}

/// Pill text after marking unread.
pub fn unread_notice() -> &'static str {
    "Marked as unread"
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pin_and_unpin() {
        let mut m = SmsMarks::default();
        assert_eq!(m.set_pinned(1, true), Ok(true));
        assert!(m.is_pinned(1));
        assert_eq!(m.set_pinned(1, true), Ok(false));
        assert_eq!(m.set_pinned(1, false), Ok(true));
        assert_eq!(m.set_pinned(1, false), Ok(false));
        assert!(!m.is_pinned(1));
    }

    #[test]
    fn pin_cap() {
        let mut m = SmsMarks::default();
        for t in 0..MAX_LOCAL_PINS as i64 {
            assert_eq!(m.set_pinned(t, true), Ok(true));
        }
        assert_eq!(m.set_pinned(99, true), Err(PinError::TooMany));
        assert!(!m.is_pinned(99));
        assert_eq!(m.set_pinned(0, true), Ok(false));
        assert_eq!(m.set_pinned(0, false), Ok(true));
        assert_eq!(m.set_pinned(99, true), Ok(true));
    }

    #[test]
    fn pin_unarchives_and_archive_unpins() {
        let mut m = SmsMarks::default();
        assert!(m.set_archived(7, true));
        assert_eq!(m.set_pinned(7, true), Ok(true));
        assert!(m.is_pinned(7) && !m.is_archived(7));
        assert!(m.set_archived(7, true));
        assert!(m.is_archived(7) && !m.is_pinned(7));
        assert!(!m.set_archived(7, true));
        assert!(m.set_archived(7, false));
        assert!(!m.set_archived(7, false));
    }

    #[test]
    fn pin_at_cap_unarchives_existing_pin_state() {
        let mut m = SmsMarks::default();
        m.pinned.insert(3);
        m.archived.insert(3);
        assert_eq!(m.set_pinned(3, true), Ok(true));
        assert!(!m.is_archived(3));
    }

    #[test]
    fn unread_marks() {
        let mut m = SmsMarks::default();
        assert!(m.set_unread(4, true));
        assert!(!m.set_unread(4, true));
        assert!(m.is_marked_unread(4));
        assert!(m.clear_unread(4));
        assert!(!m.clear_unread(4));
        assert!(m.set_unread(4, true));
        assert!(m.set_unread(4, false));
        assert!(!m.is_marked_unread(4));
    }

    #[test]
    fn forget_clears_all() {
        let mut m = SmsMarks::default();
        m.pinned.insert(1);
        m.archived.insert(1);
        m.unread.insert(1);
        m.unread.insert(2);
        assert!(m.forget(1));
        assert!(!m.forget(1));
        assert!(m.pinned.is_empty() && m.archived.is_empty());
        assert!(m.is_marked_unread(2));
    }

    #[test]
    fn rekey_moves_marks() {
        let mut m = SmsMarks::default();
        m.pinned.insert(1);
        m.unread.insert(1);
        assert!(m.rekey(1, 2));
        assert!(m.is_pinned(2) && m.is_marked_unread(2) && !m.is_pinned(1));
        assert!(!m.rekey(1, 3));
        assert!(!m.rekey(2, 2));
    }

    #[test]
    fn serde_round_trip_and_empty() {
        let mut m = SmsMarks::default();
        m.pinned.insert(1);
        m.archived.insert(2);
        m.unread.insert(3);
        let json = serde_json::to_string(&m).expect("valid json");
        assert_eq!(
            serde_json::from_str::<SmsMarks>(&json).expect("valid json"),
            m
        );
        assert_eq!(
            serde_json::from_str::<SmsMarks>("{}").expect("valid json"),
            SmsMarks::default()
        );
        let partial: SmsMarks = serde_json::from_str(r#"{"pinned":[5]}"#).expect("valid json");
        assert!(partial.is_pinned(5) && partial.archived.is_empty());
    }

    #[test]
    fn notices() {
        assert_eq!(pin_notice(true), "Pinned");
        assert_eq!(pin_notice(false), "Unpinned");
        assert_eq!(archive_notice(true), "Archived");
        assert_eq!(archive_notice(false), "Unarchived");
        assert_eq!(unread_notice(), "Marked as unread");
        assert_eq!(TOO_MANY_PINS, "You can pin up to 5 SMS conversations");
    }
}
