//! WhatsApp ids without device or agent, and the canonical key.

use std::fmt;

use serde::{Deserialize, Serialize};

const PN_SERVER: &str = "s.whatsapp.net";
const LID_SERVER: &str = "lid";
const GROUP_SERVER: &str = "g.us";
const BROADCAST_SERVER: &str = "broadcast";
const NEWSLETTER_SERVER: &str = "newsletter";
const BOT_SERVER: &str = "bot";

/// The user of the status feed on the broadcast server.
const STATUS_USER: &str = "status";

/// The announcements account (`0@s.whatsapp.net`): a service, not a person.
const PSA_USER: &str = "0";

/// What an id names.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WaIdKind {
    /// A phone number (`s.whatsapp.net`, `c.us`, `hosted`).
    Pn,
    /// A LID (`lid`, `hosted.lid`).
    Lid,
    /// A group (`g.us`).
    Group,
    /// A broadcast list.
    Broadcast,
    /// The status feed (`status@broadcast`).
    Status,
    /// A channel (`newsletter`).
    Newsletter,
    /// A bot (`bot`).
    Bot,
    /// Any other server, and the announcements account `0@s.whatsapp.net`.
    Other,
}

impl WaIdKind {
    /// Whether the id names a person (a phone number or a LID).
    #[must_use]
    pub fn is_person(self) -> bool {
        matches!(self, Self::Pn | Self::Lid)
    }
}

/// A WhatsApp id without device or agent.
///
/// Two ids are equal only when kind and user are: a phone number and a LID
/// with the same digits are different ids.
///
/// Built by [`parse`] or the constructors only, so it serializes but does not
/// deserialize: read the bare text back with [`parse`].
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize)]
pub struct WaId {
    kind: WaIdKind,
    user: String,
    /// The server as written, kept only for [`WaIdKind::Other`].
    #[serde(skip_serializing_if = "Option::is_none")]
    server: Option<String>,
}

impl WaId {
    /// The phone-number id of `digits`. `None` unless `digits` is one or more
    /// ASCII digits.
    #[must_use]
    pub fn pn(digits: &str) -> Option<Self> {
        let ok = !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit());
        ok.then(|| Self::of(WaIdKind::Pn, digits))
    }

    /// The LID of `user`. `None` when `user` is empty or holds `@` or `:`.
    #[must_use]
    pub fn lid(user: &str) -> Option<Self> {
        let ok = !user.is_empty() && !user.contains(['@', ':']);
        ok.then(|| Self::of(WaIdKind::Lid, user))
    }

    fn of(kind: WaIdKind, user: &str) -> Self {
        Self {
            kind,
            user: user.to_owned(),
            server: None,
        }
    }

    /// What the id names.
    #[must_use]
    pub fn kind(&self) -> WaIdKind {
        self.kind
    }

    /// The user part: the digits of a phone number, the LID user with its
    /// dots, the group id.
    #[must_use]
    pub fn user(&self) -> &str {
        &self.user
    }

    /// The server in its canonical form (`hosted` and `c.us` read
    /// `s.whatsapp.net`, `hosted.lid` reads `lid`).
    #[must_use]
    pub fn server(&self) -> &str {
        match self.kind {
            WaIdKind::Pn => PN_SERVER,
            WaIdKind::Lid => LID_SERVER,
            WaIdKind::Group => GROUP_SERVER,
            WaIdKind::Broadcast | WaIdKind::Status => BROADCAST_SERVER,
            WaIdKind::Newsletter => NEWSLETTER_SERVER,
            WaIdKind::Bot => BOT_SERVER,
            WaIdKind::Other => self.server.as_deref().unwrap_or_default(),
        }
    }

    /// `user@server` in the canonical server form, without device.
    #[must_use]
    pub fn bare(&self) -> String {
        format!("{}@{}", self.user, self.server())
    }

    /// `+<digits>` of a phone-number id, `None` for every other kind.
    #[must_use]
    pub fn number(&self) -> Option<String> {
        (self.kind == WaIdKind::Pn).then(|| format!("+{}", self.user))
    }
}

impl fmt::Display for WaId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}@{}", self.user, self.server())
    }
}

/// A parsed id and the device it was written with.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Parsed {
    /// The id without device or agent.
    pub id: WaId,
    /// The device suffix (`:12`), when the text had a readable one.
    pub device: Option<u16>,
}

/// Parses a JID as the gateway or the library writes it.
///
/// The text is split at the last `@`. A `:device` suffix is dropped from
/// every kind. A LID user keeps its dots. Every other kind also drops an
/// agent part (`.0` or `_0`, a number up to 255); a phone number written as
/// `digits.device` without a colon reads the number as its device. A phone
/// number must be digits only. `None` for an empty text, a text without `@`,
/// an empty user or server and a phone-number server with a non-digit user.
#[must_use]
pub fn parse(text: &str) -> Option<Parsed> {
    let (local, server) = text.trim().rsplit_once('@')?;
    if local.is_empty() || server.is_empty() {
        return None;
    }
    let kind = match server {
        PN_SERVER | "c.us" | "hosted" => WaIdKind::Pn,
        LID_SERVER | "hosted.lid" => WaIdKind::Lid,
        GROUP_SERVER => WaIdKind::Group,
        BROADCAST_SERVER => WaIdKind::Broadcast,
        NEWSLETTER_SERVER => WaIdKind::Newsletter,
        BOT_SERVER => WaIdKind::Bot,
        _ => WaIdKind::Other,
    };
    let (mut user, mut device) = match local.rsplit_once(':') {
        Some((user, device)) => (user, device.parse::<u16>().ok()),
        None => (local, None),
    };
    if kind != WaIdKind::Lid
        && let Some((head, tail)) = user.rsplit_once(['.', '_'])
        && !tail.is_empty()
        && tail.bytes().all(|b| b.is_ascii_digit())
    {
        let colon = local.contains(':');
        if kind == WaIdKind::Pn && !colon && user[head.len()..].starts_with('.') {
            // `digits.device@s.whatsapp.net`: the legacy device spelling.
            if let Ok(legacy) = tail.parse::<u16>() {
                user = head;
                device = Some(legacy);
            }
        } else if tail.parse::<u8>().is_ok() {
            user = head;
        }
    }
    if user.is_empty() {
        return None;
    }
    let id = match kind {
        WaIdKind::Pn if user == PSA_USER => WaId {
            kind: WaIdKind::Other,
            user: user.to_owned(),
            server: Some(PN_SERVER.to_owned()),
        },
        WaIdKind::Pn => WaId::pn(user)?,
        WaIdKind::Broadcast if user == STATUS_USER => WaId::of(WaIdKind::Status, user),
        WaIdKind::Other => WaId {
            kind,
            user: user.to_owned(),
            server: Some(server.to_owned()),
        },
        _ => WaId::of(kind, user),
    };
    Some(Parsed { id, device })
}

/// The key a person or chat is stored under.
///
/// For a person it reads `<digits>@s.whatsapp.net`, or `<user>@lid` while no
/// number is known; a group or any other kind keeps its own bare id.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct IdentityKey(String);

impl IdentityKey {
    /// A key read back from a store.
    #[must_use]
    pub fn from_stored(key: impl Into<String>) -> Self {
        Self(key.into())
    }

    /// The key as stored.
    #[must_use]
    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl fmt::Display for IdentityKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

/// The key of an id on its own, with nothing else known about it.
impl From<&WaId> for IdentityKey {
    fn from(id: &WaId) -> Self {
        Self(id.bare())
    }
}

/// The canonical key of a person: the current phone number when one is
/// known, else the LID. `None` when neither is given.
///
/// The only place that chooses between the two forms: a switch to LID-first
/// is a change to this function alone.
#[must_use]
pub fn canonical_key(pn: Option<&WaId>, lid: Option<&WaId>) -> Option<IdentityKey> {
    pn.or(lid).map(IdentityKey::from)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn id(text: &str) -> WaId {
        parse(text).expect("parses").id
    }

    fn device(text: &str) -> Option<u16> {
        parse(text).expect("parses").device
    }

    /// `(input, kind, user, bare, device)` for every string of the matrix.
    const PARSED: &[(&str, WaIdKind, &str, &str, Option<u16>)] = &[
        (
            "90555@s.whatsapp.net",
            WaIdKind::Pn,
            "90555",
            "90555@s.whatsapp.net",
            None,
        ),
        (
            "90555:12@s.whatsapp.net",
            WaIdKind::Pn,
            "90555",
            "90555@s.whatsapp.net",
            Some(12),
        ),
        (
            "90555.0:12@s.whatsapp.net",
            WaIdKind::Pn,
            "90555",
            "90555@s.whatsapp.net",
            Some(12),
        ),
        (
            "90555_0:12@s.whatsapp.net",
            WaIdKind::Pn,
            "90555",
            "90555@s.whatsapp.net",
            Some(12),
        ),
        ("123@lid", WaIdKind::Lid, "123", "123@lid", None),
        ("123:7@lid", WaIdKind::Lid, "123", "123@lid", Some(7)),
        ("12.34:7@lid", WaIdKind::Lid, "12.34", "12.34@lid", Some(7)),
        ("123.0:7@lid", WaIdKind::Lid, "123.0", "123.0@lid", Some(7)),
        ("1-2@g.us", WaIdKind::Group, "1-2", "1-2@g.us", None),
        (
            "status@broadcast",
            WaIdKind::Status,
            "status",
            "status@broadcast",
            None,
        ),
        (
            "1700@broadcast",
            WaIdKind::Broadcast,
            "1700",
            "1700@broadcast",
            None,
        ),
        (
            "x@newsletter",
            WaIdKind::Newsletter,
            "x",
            "x@newsletter",
            None,
        ),
        ("1@bot", WaIdKind::Bot, "1", "1@bot", None),
        (
            "90555@c.us",
            WaIdKind::Pn,
            "90555",
            "90555@s.whatsapp.net",
            None,
        ),
        (
            "90555:99@hosted",
            WaIdKind::Pn,
            "90555",
            "90555@s.whatsapp.net",
            Some(99),
        ),
        ("123@hosted.lid", WaIdKind::Lid, "123", "123@lid", None),
        (
            "0@s.whatsapp.net",
            WaIdKind::Other,
            "0",
            "0@s.whatsapp.net",
            None,
        ),
        ("0@c.us", WaIdKind::Other, "0", "0@s.whatsapp.net", None),
        (
            "abc@example.org",
            WaIdKind::Other,
            "abc",
            "abc@example.org",
            None,
        ),
        ("77:3@call", WaIdKind::Other, "77", "77@call", Some(3)),
    ];

    #[test]
    fn parses_every_listed_id() {
        for &(text, kind, user, bare, dev) in PARSED {
            let parsed = parse(text).unwrap_or_else(|| panic!("{text} parses"));
            assert_eq!(parsed.id.kind(), kind, "{text}");
            assert_eq!(parsed.id.user(), user, "{text}");
            assert_eq!(parsed.id.bare(), bare, "{text}");
            assert_eq!(parsed.id.to_string(), bare, "{text}");
            assert_eq!(parsed.device, dev, "{text}");
        }
    }

    #[test]
    fn rejects_what_is_not_an_id() {
        for text in [
            "",
            "   ",
            "90555",
            "s.whatsapp.net",
            "@s.whatsapp.net",
            "90555@",
            "abc@s.whatsapp.net",
            "+90555@s.whatsapp.net",
            "90 555@s.whatsapp.net",
            "90555x:3@s.whatsapp.net",
            "abc@c.us",
            "abc@hosted",
            ":7@lid",
            ".0:3@s.whatsapp.net",
        ] {
            assert_eq!(parse(text), None, "{text:?}");
        }
    }

    #[test]
    fn bare_form_parses_to_itself() {
        for &(text, ..) in PARSED {
            let first = id(text);
            assert_eq!(id(&first.bare()), first, "{text}");
            assert_eq!(device(&first.bare()), None, "{text}");
        }
    }

    #[test]
    fn splits_at_the_last_at_sign() {
        let parsed = id("a@b@g.us");
        assert_eq!(parsed.kind(), WaIdKind::Group);
        assert_eq!(parsed.user(), "a@b");
    }

    #[test]
    fn surrounding_space_is_ignored() {
        assert_eq!(id(" 123:7@lid\n"), id("123@lid"));
    }

    #[test]
    fn legacy_dotted_device_on_a_phone_number() {
        let parsed = parse("90555.7@s.whatsapp.net").expect("parses");
        assert_eq!(parsed.id.user(), "90555");
        assert_eq!(parsed.device, Some(7));
    }

    #[test]
    fn unreadable_device_is_dropped() {
        let parsed = parse("123:x@lid").expect("parses");
        assert_eq!(parsed.id, id("123@lid"));
        assert_eq!(parsed.device, None);
    }

    #[test]
    fn agent_is_stripped_only_when_it_is_a_small_number() {
        assert_eq!(id("120363.1@g.us").user(), "120363");
        assert_eq!(id("120363.999@g.us").user(), "120363.999");
        assert_eq!(id("news.letter@newsletter").user(), "news.letter");
        assert_eq!(parse("90555.999:1@s.whatsapp.net"), None);
    }

    #[test]
    fn every_device_of_a_person_is_one_id() {
        let pn = id("90555000001@s.whatsapp.net");
        for text in [
            "90555000001:0@s.whatsapp.net",
            "90555000001:12@s.whatsapp.net",
            "90555000001.0:12@s.whatsapp.net",
            "90555000001:99@hosted",
            "90555000001@c.us",
        ] {
            assert_eq!(id(text), pn, "{text}");
            assert_eq!(IdentityKey::from(&id(text)), IdentityKey::from(&pn));
        }
        let lid = id("123@lid");
        for text in ["123:0@lid", "123:7@lid", "123:99@hosted.lid"] {
            assert_eq!(id(text), lid, "{text}");
        }
    }

    #[test]
    fn equal_digits_on_both_servers_are_two_ids() {
        let pn = id("90555000001@s.whatsapp.net");
        let lid = id("90555000001@lid");
        assert_ne!(pn, lid);
        assert_ne!(IdentityKey::from(&pn), IdentityKey::from(&lid));
    }

    #[test]
    fn number_only_for_phone_ids() {
        assert_eq!(
            id("90555000001:3@s.whatsapp.net").number().as_deref(),
            Some("+90555000001")
        );
        for text in ["90555000001@lid", "1-2@g.us", "0@s.whatsapp.net", "1@bot"] {
            assert_eq!(id(text).number(), None, "{text}");
        }
    }

    #[test]
    fn person_kinds() {
        assert!(id("1@s.whatsapp.net").kind().is_person());
        assert!(id("1@lid").kind().is_person());
        for text in [
            "1-2@g.us",
            "status@broadcast",
            "5@broadcast",
            "x@newsletter",
            "1@bot",
            "0@s.whatsapp.net",
        ] {
            assert!(!id(text).kind().is_person(), "{text}");
        }
    }

    #[test]
    fn constructors_validate() {
        assert_eq!(WaId::pn("90555000001"), Some(id("90555000001@c.us")));
        assert_eq!(WaId::pn(""), None);
        assert_eq!(WaId::pn("9055a"), None);
        assert_eq!(WaId::lid("12.34"), Some(id("12.34@lid")));
        assert_eq!(WaId::lid(""), None);
        assert_eq!(WaId::lid("1@lid"), None);
        assert_eq!(WaId::lid("1:2"), None);
    }

    #[test]
    fn canonical_key_prefers_the_number() {
        let pn = id("90555000001@s.whatsapp.net");
        let lid = id("123@lid");
        let key = |pn, lid| canonical_key(pn, lid).map(|k| k.as_str().to_owned());
        assert_eq!(
            key(Some(&pn), Some(&lid)).as_deref(),
            Some("90555000001@s.whatsapp.net")
        );
        assert_eq!(
            key(Some(&pn), None).as_deref(),
            Some("90555000001@s.whatsapp.net")
        );
        assert_eq!(key(None, Some(&lid)).as_deref(), Some("123@lid"));
        assert_eq!(key(None, None), None);
    }

    #[test]
    fn key_and_id_serde() {
        let key = IdentityKey::from(&id("123:4@lid"));
        assert_eq!(
            serde_json::to_string(&key).expect("serializes"),
            "\"123@lid\""
        );
        let lid = id("123@lid");
        let json = serde_json::to_string(&lid).expect("serializes");
        assert_eq!(json, r#"{"kind":"lid","user":"123"}"#);
        let other = id("abc@example.org");
        let json = serde_json::to_string(&other).expect("serializes");
        assert_eq!(
            json,
            r#"{"kind":"other","user":"abc","server":"example.org"}"#
        );
    }
}
