//! `wa_identity.*`: reading a WhatsApp id (`rosty_core::wa_identity`), without a store.
//!
//! `wa_identity.parse {id}` splits a JID as the gateway or the library writes it, so the Kotlin side keeps no JID
//! parsing of its own. Who an id *is* (the key its chat is stored under, the person behind a LID) depends on what
//! the store learned: that is `wa_sync.identity_resolve`.

use super::registry::{Case, OpSpec};
use crate::envelope::FfiError;
use crate::op;
use rosty_core::wa_identity::{WaIdKind, parse};
use serde::{Deserialize, Serialize};
use serde_json::json;

pub(super) const OPS: &[OpSpec] = &[op!(
    "wa_identity.parse",
    ParseReq,
    Option<ParsedAns>,
    parse_id,
    parse_cases
)];

#[derive(Deserialize)]
struct ParseReq {
    /// A JID, device allowed (`100000000001:12@s.whatsapp.net`, `777000000000001:7@lid`).
    id: String,
}

#[derive(Serialize)]
struct ParsedAns {
    /// `pn`, `lid`, `group`, `broadcast`, `status`, `newsletter`, `bot` or `other`.
    kind: WaIdKind,
    /// The digits of a phone number, the LID user, the group id.
    user: String,
    /// `user@server` in the canonical server form, without device or agent.
    bare: String,
    /// `+<digits>` of a phone number. A LID has none: its digits are not a number.
    #[serde(skip_serializing_if = "Option::is_none")]
    number: Option<String>,
    /// The device the id was written with.
    #[serde(skip_serializing_if = "Option::is_none")]
    device: Option<u16>,
}

/// `null` when `id` is not a JID.
fn parse_id(req: ParseReq) -> Result<Option<ParsedAns>, FfiError> {
    Ok(parse(&req.id).map(|parsed| ParsedAns {
        kind: parsed.id.kind(),
        user: parsed.id.user().to_owned(),
        bare: parsed.id.bare(),
        number: parsed.id.number(),
        device: parsed.device,
    }))
}

fn parse_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"id": "100000000001:12@s.whatsapp.net"})),
        Case::named("lid", json!({"id": "777000000000001:7@lid"})),
        // The older spelling of a phone-number id reads as the same id.
        Case::named("c_us", json!({"id": "100000000001@c.us"})),
        Case::named("group", json!({"id": "120000000000000001@g.us"})),
        Case::named("status", json!({"id": "status@broadcast"})),
        Case::named("newsletter", json!({"id": "120000000000000002@newsletter"})),
        // The announcements account is a service, not a person.
        Case::named("service", json!({"id": "0@s.whatsapp.net"})),
        Case::named("not_an_id", json!({"id": "+90 555 000 00 01"})),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parsed(id: &str) -> Option<ParsedAns> {
        parse_id(ParseReq { id: id.to_owned() }).expect("parse never fails")
    }

    #[test]
    fn a_number_and_a_lid_with_the_same_digits_are_told_apart() {
        let pn = parsed("100000000001:3@s.whatsapp.net").expect("a number");
        let lid = parsed("100000000001:3@lid").expect("a lid");
        assert_eq!((pn.kind, lid.kind), (WaIdKind::Pn, WaIdKind::Lid));
        assert_eq!(pn.number.as_deref(), Some("+100000000001"));
        assert_eq!(lid.number, None);
        assert_eq!((pn.device, lid.device), (Some(3), Some(3)));
        assert_eq!(pn.bare, "100000000001@s.whatsapp.net");
        assert_eq!(lid.bare, "100000000001@lid");
    }

    #[test]
    fn text_that_is_not_an_id_is_null() {
        for text in ["", "merhaba", "@lid", "100000000001@", "abc@s.whatsapp.net"] {
            assert!(parsed(text).is_none(), "{text:?}");
        }
    }
}
