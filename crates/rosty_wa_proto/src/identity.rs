//! LID and phone-number pairs as the gateway reports them.
//!
//! WhatsApp names one person by a phone-number JID (`<digits>@s.whatsapp.net`)
//! and by a LID (`<user>@lid`). The gateway forwards every pair it learns, in
//! the `identity_mapped` event and in pages of
//! `GET /v1/identity/mappings?since=<cursor>`, so an app can join both ids to
//! one person on its own.
//!
//! Every id here is bare: `user@server`, no device and no agent.

/// Where the gateway learned a pair. The wire carries [`Self::as_str`]; a
/// name this build does not know reads as [`Self::Other`].
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum IdentitySource {
    /// WhatsApp announced that a contact changed number.
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
    /// A synced address-book entry named both ids.
    ContactAction,
    /// A history sync named both ids.
    History,
    /// A device-list notification named both ids.
    DeviceNotification,
    /// Anything else.
    #[default]
    Other,
}

impl IdentitySource {
    /// Every source, most trusted first.
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

    /// The name on the wire.
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

    /// The source a wire name stands for; an unknown name is [`Self::Other`].
    #[must_use]
    pub fn from_wire(name: &str) -> Self {
        Self::ALL
            .into_iter()
            .find(|source| source.as_str() == name)
            .unwrap_or(Self::Other)
    }

    /// How far the source is trusted when two pairs disagree: the higher rank
    /// wins, equal ranks are decided by the newer `learned_ms`.
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

impl std::fmt::Display for IdentitySource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

/// One learned pair: `lid` and `pn` are the same person.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct IdentityMapping {
    /// The LID, `<user>@lid`.
    pub lid: String,
    /// The phone-number JID, `<digits>@s.whatsapp.net`.
    pub pn: String,
    /// Where the pair comes from: an [`IdentitySource`] name. A plain string,
    /// so a source a newer gateway adds still parses.
    pub source: String,
    /// When the pair was learned (unix ms).
    pub learned_ms: i64,
}

impl IdentityMapping {
    pub fn new(
        lid: impl Into<String>,
        pn: impl Into<String>,
        source: IdentitySource,
        learned_ms: i64,
    ) -> Self {
        Self {
            lid: lid.into(),
            pn: pn.into(),
            source: source.as_str().to_owned(),
            learned_ms,
        }
    }

    /// The source, with an unknown name read as [`IdentitySource::Other`].
    #[must_use]
    pub fn source(&self) -> IdentitySource {
        IdentitySource::from_wire(&self.source)
    }
}

/// One page of `GET /v1/identity/mappings?since=<cursor>`: the pairs learned
/// or changed after `since`, oldest change first.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct IdentityMappings {
    pub items: Vec<IdentityMapping>,
    /// The cursor to pass as `since` for the page after this one. `None` when
    /// this page is empty: the caller has every pair. Keep the last cursor a
    /// page gave to ask only for newer pairs next time. Opaque.
    #[serde(default)]
    pub next: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn source_names_are_pinned() {
        let names: Vec<&str> = IdentitySource::ALL.iter().map(|s| s.as_str()).collect();
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
        for source in IdentitySource::ALL {
            assert_eq!(IdentitySource::from_wire(source.as_str()), source);
            assert_eq!(source.to_string(), source.as_str());
        }
        assert_eq!(
            IdentitySource::from_wire("from_the_future"),
            IdentitySource::Other
        );
        assert_eq!(IdentitySource::from_wire(""), IdentitySource::Other);
    }

    /// The gateway's names and ranks are the ones the device's identity map
    /// reads (`rosty_core::wa_identity::Source`).
    #[test]
    fn sources_match_the_identity_map() {
        use rosty_core::wa_identity::Source;
        assert_eq!(IdentitySource::ALL.len(), Source::ALL.len());
        for (ours, theirs) in IdentitySource::ALL.into_iter().zip(Source::ALL) {
            assert_eq!(ours.as_str(), theirs.as_str());
            assert_eq!(ours.rank(), theirs.rank());
            assert_eq!(Source::from_wire(ours.as_str()), theirs);
        }
    }

    #[test]
    fn a_page_is_pinned() {
        let page = IdentityMappings {
            items: vec![IdentityMapping::new(
                "1234@lid",
                "905550000001@s.whatsapp.net",
                IdentitySource::PeerMessage,
                1_700_000_000_000,
            )],
            next: Some("7".into()),
        };
        let text = r#"{"items":[{"lid":"1234@lid","pn":"905550000001@s.whatsapp.net","source":"peer_message","learned_ms":1700000000000}],"next":"7"}"#;
        assert_eq!(serde_json::to_string(&page).unwrap(), text);
        assert_eq!(
            serde_json::from_str::<IdentityMappings>(text).unwrap(),
            page
        );
        assert_eq!(page.items[0].source(), IdentitySource::PeerMessage);
    }

    #[test]
    fn the_last_page_has_no_cursor() {
        let empty = IdentityMappings::default();
        let text = r#"{"items":[],"next":null}"#;
        assert_eq!(serde_json::to_string(&empty).unwrap(), text);
        assert_eq!(
            serde_json::from_str::<IdentityMappings>(text).unwrap(),
            empty
        );
        // `next` may be left out altogether.
        assert_eq!(
            serde_json::from_str::<IdentityMappings>(r#"{"items":[]}"#).unwrap(),
            empty
        );
    }

    #[test]
    fn a_source_from_a_newer_gateway_still_parses() {
        let page: IdentityMappings = serde_json::from_str(
            r#"{"items":[{"lid":"1@lid","pn":"905550000002@s.whatsapp.net","source":"telepathy","learned_ms":5,"extra":true}],"next":"9","more":1}"#,
        )
        .unwrap();
        assert_eq!(page.items[0].source, "telepathy");
        assert_eq!(page.items[0].source(), IdentitySource::Other);
        assert_eq!(page.next.as_deref(), Some("9"));
    }
}
