//! Inbox filter chips (All / Unread / WhatsApp / SMS / Groups): matching, counts,
//! which chips to show, and their labels.

/// One filter chip of the inbox.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum InboxFilter {
    /// Every conversation.
    #[default]
    All,
    /// Conversations with unread messages or marked unread.
    Unread,
    /// Conversations that have a WhatsApp side.
    WhatsApp,
    /// Conversations that have an SMS side.
    Sms,
    /// Group chats.
    Groups,
}

/// Every filter in chip order; `counts` is indexed by position in this array.
pub const ALL: [InboxFilter; 5] = [
    InboxFilter::All,
    InboxFilter::Unread,
    InboxFilter::WhatsApp,
    InboxFilter::Sms,
    InboxFilter::Groups,
];

impl InboxFilter {
    /// Chip text without a count.
    pub fn label(self) -> &'static str {
        match self {
            InboxFilter::All => "All",
            InboxFilter::Unread => "Unread",
            InboxFilter::WhatsApp => "WhatsApp",
            InboxFilter::Sms => "SMS",
            InboxFilter::Groups => "Groups",
        }
    }
}

/// The facts about one inbox row that the filters look at.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct RowFacts {
    /// Unread count above zero, or marked unread.
    pub unread: bool,
    /// The row has a WhatsApp conversation.
    pub whatsapp: bool,
    /// The row has an SMS conversation.
    pub sms: bool,
    /// The row is a group chat.
    pub group: bool,
}

/// Whether a row passes `filter`. A mixed row passes both channel filters.
pub fn matches(filter: InboxFilter, facts: RowFacts) -> bool {
    match filter {
        InboxFilter::All => true,
        InboxFilter::Unread => facts.unread,
        InboxFilter::WhatsApp => facts.whatsapp,
        InboxFilter::Sms => facts.sms,
        InboxFilter::Groups => facts.group,
    }
}

/// Row count per filter, indexed by position in [`ALL`].
pub fn counts(facts: &[RowFacts]) -> [usize; 5] {
    let mut out = [0; 5];
    for f in facts {
        for (slot, filter) in out.iter_mut().zip(ALL) {
            if matches(filter, *f) {
                *slot += 1;
            }
        }
    }
    out
}

/// Chips worth showing. Empty for fewer than two rows; All and Unread otherwise;
/// WhatsApp and SMS only when both channels have rows; Groups only when present.
pub fn visible(counts: &[usize; 5]) -> Vec<InboxFilter> {
    if counts[0] < 2 {
        return Vec::new();
    }
    let mut out = vec![InboxFilter::All, InboxFilter::Unread];
    if counts[2] > 0 && counts[3] > 0 {
        out.push(InboxFilter::WhatsApp);
        out.push(InboxFilter::Sms);
    }
    if counts[4] > 0 {
        out.push(InboxFilter::Groups);
    }
    out
}

/// Chip text with its count ("Unread 3"); All and zero counts show no number.
pub fn chip_label(filter: InboxFilter, count: usize) -> String {
    if filter == InboxFilter::All || count == 0 {
        filter.label().to_string()
    } else {
        format!("{} {count}", filter.label())
    }
}

/// Text shown when `filter` matches no rows.
pub fn empty_text(filter: InboxFilter) -> &'static str {
    match filter {
        InboxFilter::All => "No conversations",
        InboxFilter::Unread => "No unread chats",
        InboxFilter::WhatsApp => "No WhatsApp chats",
        InboxFilter::Sms => "No SMS conversations",
        InboxFilter::Groups => "No group chats",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wa() -> RowFacts {
        RowFacts {
            whatsapp: true,
            ..Default::default()
        }
    }
    fn sms() -> RowFacts {
        RowFacts {
            sms: true,
            ..Default::default()
        }
    }
    fn mixed() -> RowFacts {
        RowFacts {
            whatsapp: true,
            sms: true,
            unread: true,
            group: false,
        }
    }

    #[test]
    fn default_is_all() {
        assert_eq!(InboxFilter::default(), InboxFilter::All);
    }

    #[test]
    fn matches_every_arm() {
        let none = RowFacts::default();
        assert!(matches(InboxFilter::All, none));
        assert!(!matches(InboxFilter::Unread, none));
        assert!(matches(
            InboxFilter::Unread,
            RowFacts {
                unread: true,
                ..none
            }
        ));
        assert!(matches(InboxFilter::WhatsApp, wa()));
        assert!(!matches(InboxFilter::WhatsApp, sms()));
        assert!(matches(InboxFilter::Sms, sms()));
        assert!(!matches(InboxFilter::Sms, wa()));
        assert!(matches(
            InboxFilter::Groups,
            RowFacts {
                group: true,
                ..none
            }
        ));
        assert!(!matches(InboxFilter::Groups, wa()));
    }

    #[test]
    fn mixed_row_matches_both_channels() {
        assert!(matches(InboxFilter::WhatsApp, mixed()));
        assert!(matches(InboxFilter::Sms, mixed()));
    }

    #[test]
    fn counts_rows() {
        let g = RowFacts {
            whatsapp: true,
            group: true,
            unread: true,
            sms: false,
        };
        let c = counts(&[wa(), sms(), mixed(), g]);
        assert_eq!(c, [4, 2, 3, 2, 1]);
        assert_eq!(counts(&[]), [0; 5]);
    }

    #[test]
    fn visible_rules() {
        assert!(visible(&[1, 1, 1, 0, 0]).is_empty());
        assert!(visible(&[0; 5]).is_empty());
        assert_eq!(
            visible(&[3, 0, 3, 0, 0]),
            vec![InboxFilter::All, InboxFilter::Unread]
        );
        assert_eq!(
            visible(&[3, 0, 0, 3, 0]),
            vec![InboxFilter::All, InboxFilter::Unread]
        );
        assert_eq!(
            visible(&[3, 0, 1, 2, 0]),
            vec![
                InboxFilter::All,
                InboxFilter::Unread,
                InboxFilter::WhatsApp,
                InboxFilter::Sms
            ]
        );
        assert_eq!(
            visible(&[3, 0, 3, 0, 1]),
            vec![InboxFilter::All, InboxFilter::Unread, InboxFilter::Groups]
        );
    }

    #[test]
    fn chip_labels() {
        assert_eq!(chip_label(InboxFilter::All, 9), "All");
        assert_eq!(chip_label(InboxFilter::Unread, 3), "Unread 3");
        assert_eq!(chip_label(InboxFilter::Unread, 0), "Unread");
        assert_eq!(chip_label(InboxFilter::WhatsApp, 12), "WhatsApp 12");
        assert_eq!(chip_label(InboxFilter::Sms, 2), "SMS 2");
        assert_eq!(chip_label(InboxFilter::Groups, 1), "Groups 1");
    }

    #[test]
    fn empty_texts() {
        assert_eq!(empty_text(InboxFilter::All), "No conversations");
        assert_eq!(empty_text(InboxFilter::Unread), "No unread chats");
        assert_eq!(empty_text(InboxFilter::WhatsApp), "No WhatsApp chats");
        assert_eq!(empty_text(InboxFilter::Sms), "No SMS conversations");
        assert_eq!(empty_text(InboxFilter::Groups), "No group chats");
    }
}
