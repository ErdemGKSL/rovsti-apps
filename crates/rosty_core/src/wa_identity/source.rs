//! Where a LID and phone-number pair was learned, and how far it is trusted.

use serde::{Deserialize, Serialize};

/// Where a pair was learned. The names are the wire names of the gateway's
/// `source` field; a name this build does not know reads as [`Source::Other`].
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Source {
    /// The server announced that a contact changed number.
    NumberChange,
    /// A message stanza named both ids.
    PeerMessage,
    /// A receipt stanza named both ids.
    Receipt,
    /// A call offer named both ids.
    Call,
    /// A contact lookup answered with both ids.
    Usync,
    /// A group's member list named both ids.
    GroupMetadata,
    /// The phone pushed its migration table.
    MigrationSync,
    /// A synced contact entry named both ids.
    ContactAction,
    /// History sync named both ids.
    History,
    /// A device list notification named both ids.
    DeviceNotification,
    /// Anything else, and a person first seen under one id only.
    #[default]
    #[serde(other)]
    Other,
}

impl Source {
    /// Every source, strongest first.
    pub const ALL: [Self; 11] = [
        Self::NumberChange,
        Self::PeerMessage,
        Self::Receipt,
        Self::Call,
        Self::Usync,
        Self::GroupMetadata,
        Self::MigrationSync,
        Self::ContactAction,
        Self::History,
        Self::DeviceNotification,
        Self::Other,
    ];

    /// The wire name, also the value stored in the `alias.source` column.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::NumberChange => "number_change",
            Self::PeerMessage => "peer_message",
            Self::Receipt => "receipt",
            Self::Call => "call",
            Self::Usync => "usync",
            Self::GroupMetadata => "group_metadata",
            Self::MigrationSync => "migration_sync",
            Self::ContactAction => "contact_action",
            Self::History => "history",
            Self::DeviceNotification => "device_notification",
            Self::Other => "other",
        }
    }

    /// The source of a wire or stored name; an unknown name is
    /// [`Source::Other`].
    #[must_use]
    pub fn from_wire(name: &str) -> Self {
        Self::ALL
            .into_iter()
            .find(|source| source.as_str() == name)
            .unwrap_or(Self::Other)
    }

    /// How far the source is trusted on a conflict: the higher rank wins, and
    /// equal ranks are decided by the newer `learned_ms`.
    #[must_use]
    pub fn rank(self) -> u8 {
        match self {
            Self::NumberChange => 6,
            Self::PeerMessage | Self::Receipt | Self::Call => 5,
            Self::Usync | Self::GroupMetadata => 4,
            Self::MigrationSync => 3,
            Self::ContactAction => 2,
            Self::History | Self::DeviceNotification | Self::Other => 1,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wire_names_match_serde() {
        for source in Source::ALL {
            let json = serde_json::to_string(&source).expect("serializes");
            assert_eq!(json, format!("\"{}\"", source.as_str()));
            assert_eq!(
                serde_json::from_str::<Source>(&json).expect("deserializes"),
                source
            );
            assert_eq!(Source::from_wire(source.as_str()), source);
        }
    }

    #[test]
    fn wire_names_are_pinned() {
        let names: Vec<_> = Source::ALL.into_iter().map(Source::as_str).collect();
        assert_eq!(
            names,
            [
                "number_change",
                "peer_message",
                "receipt",
                "call",
                "usync",
                "group_metadata",
                "migration_sync",
                "contact_action",
                "history",
                "device_notification",
                "other",
            ]
        );
    }

    #[test]
    fn unknown_name_is_other() {
        assert_eq!(
            serde_json::from_str::<Source>("\"pairing\"").expect("deserializes"),
            Source::Other
        );
        assert_eq!(Source::from_wire("history_old"), Source::Other);
        assert_eq!(Source::from_wire(""), Source::Other);
    }

    #[test]
    fn rank_follows_the_research_order() {
        use Source::{
            Call, ContactAction, DeviceNotification, GroupMetadata, History, MigrationSync,
            NumberChange, Other, PeerMessage, Receipt, Usync,
        };
        let tiers: [&[Source]; 6] = [
            &[NumberChange],
            &[PeerMessage, Receipt, Call],
            &[Usync, GroupMetadata],
            &[MigrationSync],
            &[ContactAction],
            &[History, DeviceNotification, Other],
        ];
        for pair in tiers.windows(2) {
            for high in pair[0] {
                for low in pair[1] {
                    assert!(high.rank() > low.rank(), "{high:?} > {low:?}");
                }
            }
        }
        for tier in tiers {
            for source in tier {
                assert_eq!(source.rank(), tier[0].rank(), "{source:?}");
            }
        }
    }
}
