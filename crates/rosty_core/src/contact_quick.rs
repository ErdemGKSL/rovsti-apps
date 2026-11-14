//! Quick actions offered on a contact row (long press or swipe).

/// One quick action.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum QuickAction {
    Call,
    Message,
    WhatsApp,
    CopyNumber,
    Share,
    Star,
    Unstar,
    AddToHome,
}

impl QuickAction {
    pub fn label(self) -> &'static str {
        match self {
            Self::Call => "Call",
            Self::Message => "Message",
            Self::WhatsApp => "WhatsApp",
            Self::CopyNumber => "Copy number",
            Self::Share => "Share",
            Self::Star => "Add to favorites",
            Self::Unstar => "Remove from favorites",
            Self::AddToHome => "Add to home screen",
        }
    }
}

/// The actions for a contact, in menu order. Call, message, WhatsApp and
/// copy need a number; WhatsApp also needs the contact to be on it.
pub fn actions(has_number: bool, on_whatsapp: bool, starred: bool) -> Vec<QuickAction> {
    let mut out = Vec::with_capacity(8);
    if has_number {
        out.push(QuickAction::Call);
        out.push(QuickAction::Message);
        if on_whatsapp {
            out.push(QuickAction::WhatsApp);
        }
        out.push(QuickAction::CopyNumber);
    }
    out.push(QuickAction::Share);
    out.push(if starred {
        QuickAction::Unstar
    } else {
        QuickAction::Star
    });
    out.push(QuickAction::AddToHome);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use QuickAction::*;

    #[test]
    fn full_set() {
        assert_eq!(
            actions(true, true, false),
            [Call, Message, WhatsApp, CopyNumber, Share, Star, AddToHome]
        );
    }

    #[test]
    fn whatsapp_only_when_on_whatsapp() {
        assert!(!actions(true, false, false).contains(&WhatsApp));
    }

    #[test]
    fn no_number_drops_number_entries() {
        assert_eq!(actions(false, true, false), [Share, Star, AddToHome]);
    }

    #[test]
    fn starred_offers_unstar_and_home_is_last() {
        for flags in [
            (true, true, true),
            (false, false, true),
            (true, false, false),
        ] {
            let a = actions(flags.0, flags.1, flags.2);
            assert_eq!(a.last(), Some(&AddToHome));
            assert_eq!(a.contains(&Unstar), flags.2);
            assert_eq!(a.contains(&Star), !flags.2);
        }
    }

    #[test]
    fn labels_are_distinct() {
        let all = [
            Call, Message, WhatsApp, CopyNumber, Share, Star, Unstar, AddToHome,
        ];
        let mut labels: Vec<_> = all.iter().map(|a| a.label()).collect();
        labels.sort();
        labels.dedup();
        assert_eq!(labels.len(), all.len());
    }
}
