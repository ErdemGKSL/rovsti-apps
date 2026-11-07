//! State of the "jump to latest" button and the unread divider in a chat.

const BADGE_CAP: u32 = 99;

/// Tracks whether the user is scrolled away from the newest message and how
/// many incoming messages arrived meanwhile.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct JumpState {
    away: bool,
    unseen: u32,
}

impl JumpState {
    /// Called on every scroll; reaching the end clears the badge.
    pub fn scrolled(&mut self, at_end: bool) {
        if at_end {
            self.away = false;
            self.unseen = 0;
        } else {
            self.away = true;
        }
    }

    /// Incoming messages count only while the user is away from the end.
    pub fn arrived(&mut self, incoming: u32) {
        if self.away {
            self.unseen = self.unseen.saturating_add(incoming);
        }
    }

    /// Whether the jump button should be shown.
    pub fn visible(&self) -> bool {
        self.away
    }

    /// Badge text: "3", "99+", or `None` when nothing is unseen.
    pub fn badge(&self) -> Option<String> {
        match self.unseen {
            0 => None,
            n if n > BADGE_CAP => Some(format!("{BADGE_CAP}+")),
            n => Some(n.to_string()),
        }
    }
}

/// Index of the item where the last `unread` incoming items begin
/// (oldest-first input). Falls back to the first incoming item when fewer
/// are loaded; `None` when there is nothing unread or no incoming item.
pub fn unread_divider(incoming: &[bool], unread: u32) -> Option<usize> {
    if unread == 0 {
        return None;
    }
    let mut left = unread;
    let mut found = None;
    for (i, _) in incoming.iter().enumerate().rev().filter(|(_, inc)| **inc) {
        found = Some(i);
        left -= 1;
        if left == 0 {
            break;
        }
    }
    found
}

/// Text of the unread divider.
/// How many items a page reload added after the ones already shown, for
/// [`JumpState::arrived`] (a reload that dropped items adds none).
pub fn newly_arrived(before: usize, after: usize) -> u32 {
    u32::try_from(after.saturating_sub(before)).unwrap_or(u32::MAX)
}

pub fn divider_label(unread: u32) -> String {
    if unread == 1 {
        "1 unread message".to_string()
    } else {
        format!("{unread} unread messages")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn badge_counts_only_while_away() {
        let mut s = JumpState::default();
        s.arrived(2);
        assert_eq!(s.badge(), None);
        assert!(!s.visible());
        s.scrolled(false);
        assert!(s.visible());
        s.arrived(2);
        s.arrived(1);
        assert_eq!(s.badge().as_deref(), Some("3"));
    }

    #[test]
    fn badge_caps_at_99() {
        let mut s = JumpState::default();
        s.scrolled(false);
        s.arrived(99);
        assert_eq!(s.badge().as_deref(), Some("99"));
        s.arrived(1);
        assert_eq!(s.badge().as_deref(), Some("99+"));
        s.arrived(u32::MAX);
        assert_eq!(s.badge().as_deref(), Some("99+"));
    }

    #[test]
    fn reaching_the_end_clears() {
        let mut s = JumpState::default();
        s.scrolled(false);
        s.arrived(4);
        s.scrolled(true);
        assert!(!s.visible());
        assert_eq!(s.badge(), None);
        s.arrived(1);
        assert_eq!(s.badge(), None);
    }

    #[test]
    fn divider_before_the_last_unread_incoming() {
        // out, in, in, out, in, in
        let items = [false, true, true, false, true, true];
        assert_eq!(unread_divider(&items, 2), Some(4));
        assert_eq!(unread_divider(&items, 3), Some(2));
        assert_eq!(unread_divider(&items, 0), None);
    }

    #[test]
    fn divider_with_fewer_loaded() {
        let items = [false, true, false, true];
        assert_eq!(unread_divider(&items, 10), Some(1));
        assert_eq!(unread_divider(&[false, false], 3), None);
        assert_eq!(unread_divider(&[], 1), None);
    }

    #[test]
    fn newly_arrived_counts_growth_only() {
        assert_eq!(newly_arrived(3, 5), 2);
        assert_eq!(newly_arrived(5, 5), 0);
        assert_eq!(newly_arrived(5, 3), 0);
    }

    #[test]
    fn divider_label_plural() {
        assert_eq!(divider_label(1), "1 unread message");
        assert_eq!(divider_label(5), "5 unread messages");
    }
}
