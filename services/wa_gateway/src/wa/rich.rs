//! The WhatsApp message of a poll, a place or contact cards the gateway sends (E6-WA R5). whatsapp-rust 0.7.0 has
//! no sender for places and cards, and its poll sender takes no send options (so no disappearing timer): the
//! gateway builds each protobuf itself and sends it like any other message.

use rosty_wa_proto::RichContent;
use whatsapp_rust::waproto::buffa::MessageField;
use whatsapp_rust::waproto::whatsapp as wa;

use super::poll;
use crate::driver::DriverError;

fn context_field(context: Option<wa::ContextInfo>) -> MessageField<wa::ContextInfo> {
    context.map_or_else(MessageField::none, MessageField::some)
}

/// A fresh poll secret (32 random bytes, as WhatsApp Web makes one).
fn new_secret() -> Result<Vec<u8>, DriverError> {
    let mut secret = vec![0u8; poll::SECRET_LEN];
    getrandom::fill(&mut secret)
        .map_err(|e| DriverError::Failed(format!("no randomness for a poll secret: {e}")))?;
    Ok(secret)
}

/// The message for `content` (already checked) with the reply `context`, and the poll's secret for a poll.
pub fn rich_message(
    content: &RichContent,
    context: Option<wa::ContextInfo>,
) -> Result<(wa::Message, Option<Vec<u8>>), DriverError> {
    Ok(match content {
        RichContent::Poll {
            question,
            options,
            multiple,
        } => {
            let secret = new_secret()?;
            (
                poll::creation_message(question, options, *multiple, secret.clone(), context),
                Some(secret),
            )
        }
        RichContent::Location {
            latitude,
            longitude,
            name,
            address,
        } => (
            wa::Message {
                location_message: MessageField::some(wa::message::LocationMessage {
                    degrees_latitude: Some(*latitude),
                    degrees_longitude: Some(*longitude),
                    name: name.clone(),
                    address: address.clone(),
                    context_info: context_field(context),
                    ..Default::default()
                }),
                ..Default::default()
            },
            None,
        ),
        RichContent::Contacts { contacts } => {
            let card = |c: &rosty_wa_proto::SharedContact, context| wa::message::ContactMessage {
                display_name: Some(c.display_name.clone()),
                vcard: Some(c.vcard.clone()),
                context_info: context_field(context),
                ..Default::default()
            };
            let message = match contacts.as_slice() {
                [one] => wa::Message {
                    contact_message: MessageField::some(card(one, context)),
                    ..Default::default()
                },
                many => wa::Message {
                    contacts_array_message: MessageField::some(wa::message::ContactsArrayMessage {
                        display_name: Some(format!("{} contacts", many.len())),
                        contacts: many.iter().map(|c| card(c, None)).collect(),
                        context_info: context_field(context),
                        ..Default::default()
                    }),
                    ..Default::default()
                },
            };
            (message, None)
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wa::convert;
    use rosty_wa_proto::SharedContact;

    fn card(name: &str) -> SharedContact {
        SharedContact {
            display_name: name.into(),
            vcard: format!("BEGIN:VCARD\nFN:{name}\nEND:VCARD"),
        }
    }

    #[test]
    fn a_sent_poll_place_or_card_reads_back_as_the_same_meta() {
        let (poll, secret) = rich_message(
            &RichContent::Poll {
                question: "Lunch?".into(),
                options: vec!["Pizza".into(), "Soup".into()],
                multiple: true,
            },
            None,
        )
        .unwrap();
        let extras = convert::extras(&poll);
        let read = extras.meta.poll.unwrap();
        assert_eq!(read.question, "Lunch?");
        assert_eq!(read.selectable, 2);
        assert_eq!(secret.as_deref().map(<[u8]>::len), Some(32));
        assert_eq!(extras.secret, secret, "the secret travels in the message");

        let (place, _) = rich_message(
            &RichContent::Location {
                latitude: 41.0,
                longitude: 29.0,
                name: None,
                address: Some("Galata".into()),
            },
            None,
        )
        .unwrap();
        let location = convert::extras(&place).meta.location.unwrap();
        assert_eq!((location.latitude, location.longitude), (41.0, 29.0));
        assert_eq!(location.name, None);
        assert_eq!(location.address.as_deref(), Some("Galata"));

        let (one, _) = rich_message(
            &RichContent::Contacts {
                contacts: vec![card("Ada")],
            },
            None,
        )
        .unwrap();
        let meta = convert::extras(&one).meta;
        assert_eq!(meta.contact, Some(card("Ada")));
        assert!(meta.contacts.is_empty());

        let (two, _) = rich_message(
            &RichContent::Contacts {
                contacts: vec![card("Ada"), card("Ben")],
            },
            None,
        )
        .unwrap();
        let meta = convert::extras(&two).meta;
        assert_eq!(meta.contacts, vec![card("Ada"), card("Ben")]);
        assert_eq!(
            meta.contact,
            Some(card("Ada")),
            "the first card for older apps"
        );
    }

    #[test]
    fn a_reply_context_goes_on_the_message() {
        let context = wa::ContextInfo {
            stanza_id: Some("Q1".into()),
            ..Default::default()
        };
        let (place, _) = rich_message(
            &RichContent::Location {
                latitude: 1.0,
                longitude: 2.0,
                name: None,
                address: None,
            },
            Some(context),
        )
        .unwrap();
        assert_eq!(
            convert::extras(&place).meta.quoted.map(|q| q.id).as_deref(),
            Some("Q1")
        );
    }
}
