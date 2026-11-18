//! What arrives besides plain messages: edits (including encrypted ones),
//! deletes, and the app-state and profile events that other devices and
//! WhatsApp send (archive, pin, mute, star, push names, pictures, about).

use rosty_wa_proto::{GatewayEvent, IdentitySource, MUTED_FOREVER, MediaKind, MessageBody};
use whatsapp_rust::features::message_edit;
use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore::types::events::ContactNumberChanged;
use whatsapp_rust::wacore::types::lid_pn::LearningSource;

use super::convert::{self, Converted, Origin};
use super::lid::{LidMap, Pair, Resolver};
use crate::driver::EventSink;
use crate::store::{ChatChange, MessageChange, ProfileChange};

/// Stores and publishes what a live message turned into.
pub async fn apply_converted(sink: &EventSink, converted: Converted) {
    match converted {
        Converted::Message(message, extras) => sink.emit_full(message, *extras).await,
        Converted::Reaction {
            chat,
            target_id,
            reaction,
        } => sink.emit_reaction(chat, target_id, reaction).await,
        Converted::Edit {
            chat,
            target_id,
            body,
            edited_ms,
        } => {
            sink.change_message(&chat, &target_id, MessageChange::Edit { body, edited_ms })
                .await;
        }
        Converted::Revoke {
            chat,
            target_id,
            by,
            at_ms,
        } => {
            sink.change_message(&chat, &target_id, MessageChange::Revoke { by, at_ms })
                .await;
        }
        Converted::Disappearing {
            chat,
            id,
            seconds,
            at_ms,
            by,
        } => {
            sink.chat_timer_changed(crate::driver::TimerChange {
                chat,
                seconds,
                at_ms,
                by: Some(by),
                note_id: id,
            })
            .await;
        }
        Converted::Ignore => {}
    }
}

/// An encrypted edit, waiting for the secret of the message it edits.
#[derive(Clone, Debug)]
pub struct PendingEdit {
    id: String,
    chat: String,
    sender: Option<String>,
    from_me: bool,
    timestamp_ms: i64,
    target_id: String,
    payload: Vec<u8>,
    iv: Vec<u8>,
    author: Jid,
    author_alt: Option<Jid>,
}

/// The encrypted edit `ctx` carries, if it is one.
pub fn encrypted_edit(ctx: &MessageContext, map: &LidMap) -> Option<PendingEdit> {
    let envelope = message_edit::extract_envelope(&ctx.message)?;
    let target_id = envelope.target_id()?.to_owned();
    let source = &ctx.info.source;
    let device = ctx.client.persistence_manager().get_device_snapshot();
    let own = if source.sender.is_lid() {
        device.lid.clone()
    } else {
        device.pn.clone()
    }?;
    let author = envelope.original_sender_for_dispatch(source.is_from_me, &source.sender, &own);
    let (chat, sender) = convert::chat_and_sender(source, ctx.info.peer_recipient_pn.as_ref(), map);
    Some(PendingEdit {
        id: ctx.info.id.to_string(),
        chat,
        sender,
        from_me: source.is_from_me,
        timestamp_ms: ctx.info.timestamp.timestamp_millis(),
        target_id,
        payload: envelope.enc_payload.to_vec(),
        iv: envelope.enc_iv.to_vec(),
        author_alt: if source.is_from_me {
            None
        } else {
            source.sender_alt.clone()
        },
        author,
    })
}

impl PendingEdit {
    /// Decrypts the edit with the stored secret of the edited message. An
    /// edit of a message the gateway never saw is dropped.
    pub async fn resolve(self, sink: &EventSink) -> Converted {
        let Some(secret) = sink.message_secret(&self.chat, &self.target_id).await else {
            log::debug!("edit of unknown message {} dropped", self.target_id);
            return Converted::Ignore;
        };
        // An author can only edit their own message: editor and original
        // sender are the same person, tried by LID and by phone number.
        let alt = self.author_alt.as_ref();
        let inner = match message_edit::decrypt_with_fallback(
            &self.payload,
            &self.iv,
            &secret,
            &self.target_id,
            &self.author,
            &self.author,
            alt,
            alt,
        ) {
            Ok(inner) => inner,
            Err(e) => {
                log::warn!("cannot decrypt an edit of {}: {e}", self.target_id);
                return Converted::Ignore;
            }
        };
        let Some(legacy) = message_edit::rewrap_as_legacy_edit(inner) else {
            return Converted::Ignore;
        };
        convert::convert(
            Origin::new(
                &self.id,
                &self.chat,
                self.sender.as_deref(),
                self.from_me,
                self.timestamp_ms,
            ),
            &legacy,
        )
    }
}

/// The content that edits the caption of a media message of `kind`. `None`
/// for media without a caption (voice notes, stickers).
pub fn caption_edit(kind: MediaKind, caption: &str) -> Option<wa::Message> {
    let caption = Some(caption.to_owned());
    let mut message = wa::Message::default();
    match kind {
        MediaKind::Image => {
            message.image_message = MessageField::some(wa::message::ImageMessage {
                caption,
                ..Default::default()
            });
        }
        MediaKind::Video => {
            message.video_message = MessageField::some(wa::message::VideoMessage {
                caption,
                ..Default::default()
            });
        }
        MediaKind::Document => {
            message.document_message = MessageField::some(wa::message::DocumentMessage {
                caption,
                ..Default::default()
            });
        }
        MediaKind::Audio | MediaKind::Sticker => return None,
    }
    Some(message)
}

/// A mute action as a stored setting.
pub fn mute_change(muted: Option<bool>, end_ms: Option<i64>) -> ChatChange {
    if !muted.unwrap_or(false) {
        return ChatChange::Muted(None);
    }
    match end_ms {
        Some(end) if end > 0 => ChatChange::Muted(Some(end)),
        _ => ChatChange::Muted(Some(MUTED_FOREVER)),
    }
}

/// The pair a synced address-book entry states: the contact's LID and
/// phone-number JID, of which the entry is keyed by one (`jid`) and names
/// the other (`lidJid` / `pnJid`).
pub fn contact_pair(jid: &Jid, action: &wa::sync_action_value::ContactAction) -> Option<Pair> {
    let named = |id: &Option<String>| {
        id.as_deref()
            .filter(|id| !id.is_empty())?
            .parse::<Jid>()
            .ok()
    };
    let (lid, pn) = (named(&action.lid_jid), named(&action.pn_jid));
    match (lid, pn) {
        (Some(lid), Some(pn)) => Pair::of(&lid, &pn),
        (Some(other), None) | (None, Some(other)) => Pair::of(jid, &other),
        (None, None) => None,
    }
}

/// What the apps hear when WhatsApp says a contact changed number.
pub fn number_changed(change: &ContactNumberChanged) -> GatewayEvent {
    let bare = |jid: &Jid| jid.to_non_ad().to_string();
    GatewayEvent::IdentityNumberChanged {
        old_pn: bare(&change.old_jid),
        new_pn: bare(&change.new_jid),
        old_lid: change.old_lid.as_ref().map(bare),
        new_lid: change.new_lid.as_ref().map(bare),
    }
}

/// Tells the apps about a number change and records its pairs.
pub async fn on_number_changed(sink: &EventSink, change: &ContactNumberChanged) {
    let GatewayEvent::IdentityNumberChanged {
        old_pn,
        new_pn,
        old_lid,
        new_lid,
    } = number_changed(change)
    else {
        return;
    };
    sink.number_changed(&old_pn, &new_pn, old_lid.as_deref(), new_lid.as_deref())
        .await;
}

/// The chat JID an app-state event names, as the store keys it.
/// A LID is stored under its phone number once the client knows it.
async fn chat_of(resolver: &Resolver, jid: &Jid) -> String {
    resolver.resolve(jid, None).await.to_string()
}

/// Applies the app-state and profile events; other events are ignored.
pub async fn handle(sink: &EventSink, resolver: &Resolver, event: &Event) {
    match event {
        Event::PinUpdate(u) => {
            let pinned = u.action.pinned.unwrap_or(false);
            sink.change_chat(&chat_of(resolver, &u.jid).await, ChatChange::Pinned(pinned))
                .await;
        }
        Event::ArchiveUpdate(u) => {
            let archived = u.action.archived.unwrap_or(false);
            sink.change_chat(
                &chat_of(resolver, &u.jid).await,
                ChatChange::Archived(archived),
            )
            .await;
        }
        Event::MuteUpdate(u) => {
            let change = mute_change(u.action.muted, u.action.mute_end_timestamp);
            sink.change_chat(&chat_of(resolver, &u.jid).await, change)
                .await;
        }
        Event::MarkChatAsReadUpdate(u) => {
            let read = u.action.read.unwrap_or(true);
            let chat = chat_of(resolver, &u.jid).await;
            if read {
                sink.mark_chat_read(&chat).await;
            }
            sink.change_chat(&chat, ChatChange::MarkedUnread(!read))
                .await;
        }
        Event::StarUpdate(u) => {
            let starred = u.action.starred.unwrap_or(false);
            sink.change_message(
                &chat_of(resolver, &u.chat_jid).await,
                &u.message_id,
                MessageChange::Star(starred),
            )
            .await;
        }
        Event::DeleteMessageForMeUpdate(u) => {
            sink.delete_message(&chat_of(resolver, &u.chat_jid).await, &u.message_id)
                .await;
        }
        Event::DeleteChatUpdate(u) => {
            sink.delete_chat(&chat_of(resolver, &u.jid).await, false)
                .await
        }
        Event::ClearChatUpdate(u) => {
            sink.delete_chat(&chat_of(resolver, &u.jid).await, true)
                .await
        }
        Event::PushNameUpdate(u) => {
            sink.change_profile(
                &chat_of(resolver, &u.jid).await,
                ProfileChange::PushName(u.new_push_name.clone()),
            )
            .await;
        }
        Event::ContactUpdate(u) => {
            // whatsapp-rust does not read the ids of a synced contact; they
            // are how a saved contact's LID meets its number.
            if let Some(pair) = contact_pair(&u.jid, &u.action) {
                let fed = resolver
                    .client()
                    .add_lid_pn_mapping(&pair.lid.user, &pair.pn.user, LearningSource::Other)
                    .await;
                if let Err(e) = fed {
                    log::debug!("feeding a contact's LID to the client failed: {e:?}");
                }
                sink.learn_pairs(vec![pair.learned(IdentitySource::ContactAction)])
                    .await;
            }
            let name = u
                .action
                .full_name
                .clone()
                .or_else(|| u.action.first_name.clone());
            sink.change_profile(
                &chat_of(resolver, &u.jid).await,
                ProfileChange::ContactName(name),
            )
            .await;
        }
        Event::UserAboutUpdate(u) => {
            sink.change_profile(
                &chat_of(resolver, &u.jid).await,
                ProfileChange::About(Some(u.status.clone()).filter(|s| !s.is_empty())),
            )
            .await;
        }
        Event::PictureUpdate(u) => {
            let change = match (u.removed, &u.picture_id) {
                (true, _) => ProfileChange::Picture(None),
                (false, Some(id)) if !id.is_empty() => ProfileChange::Picture(Some(id.clone())),
                // Changed, but to what is not said: ask again next time.
                (false, _) => ProfileChange::Stale,
            };
            sink.change_profile(&chat_of(resolver, &u.jid).await, change)
                .await;
        }
        Event::ContactUpdated(u) => {
            sink.change_profile(&chat_of(resolver, &u.jid).await, ProfileChange::Stale)
                .await;
        }
        _ => {}
    }
}

/// Whether a body can be edited: texts and captions of photos, videos and
/// documents.
pub fn is_editable(body: &MessageBody) -> bool {
    match body {
        MessageBody::Text(_) => true,
        MessageBody::Media { kind, .. } => !matches!(kind, MediaKind::Audio | MediaKind::Sticker),
        MessageBody::Unsupported => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mute_actions() {
        assert_eq!(mute_change(Some(false), Some(5)), ChatChange::Muted(None));
        assert_eq!(mute_change(None, None), ChatChange::Muted(None));
        assert_eq!(
            mute_change(Some(true), Some(-1)),
            ChatChange::Muted(Some(MUTED_FOREVER))
        );
        assert_eq!(
            mute_change(Some(true), Some(1_800_000_000_000)),
            ChatChange::Muted(Some(1_800_000_000_000))
        );
    }

    fn jid(s: &str) -> Jid {
        s.parse().unwrap()
    }

    #[test]
    fn synced_contacts_state_pairs() {
        use wa::sync_action_value::ContactAction;
        let lid = jid("1234@lid");
        let pn = jid("905550000001@s.whatsapp.net");
        let pair = Pair::of(&lid, &pn);
        assert!(pair.is_some());
        let action = |lid_jid: Option<&str>, pn_jid: Option<&str>| ContactAction {
            full_name: Some("Ayse".into()),
            lid_jid: lid_jid.map(str::to_owned),
            pn_jid: pn_jid.map(str::to_owned),
            ..Default::default()
        };
        // Keyed by the number, naming the LID; and the reverse.
        assert_eq!(contact_pair(&pn, &action(Some("1234@lid"), None)), pair);
        assert_eq!(
            contact_pair(&lid, &action(None, Some("905550000001@s.whatsapp.net"))),
            pair
        );
        // Both named: they are the pair, whatever the key says.
        assert_eq!(
            contact_pair(
                &jid("905550000009@s.whatsapp.net"),
                &action(Some("1234:3@lid"), Some("905550000001:1@s.whatsapp.net"))
            ),
            pair
        );
        // Nothing named, the same kind twice, or text that is no id.
        assert_eq!(contact_pair(&pn, &action(None, None)), None);
        assert_eq!(contact_pair(&lid, &action(Some("5@lid"), None)), None);
        assert_eq!(contact_pair(&pn, &action(Some(""), Some(""))), None);
        assert_eq!(contact_pair(&pn, &action(Some("@@"), None)), None);
    }

    #[test]
    fn a_number_change_names_bare_ids() {
        let at = whatsapp_rust::wacore::chrono::DateTime::from_timestamp(1, 0).unwrap();
        let change = ContactNumberChanged::builder()
            .old_jid(jid("905550000001:2@s.whatsapp.net"))
            .new_jid(jid("905550000002@s.whatsapp.net"))
            .old_lid(jid("1234:7@lid"))
            .new_lid(jid("1234@lid"))
            .timestamp(at)
            .build();
        assert_eq!(
            number_changed(&change),
            GatewayEvent::IdentityNumberChanged {
                old_pn: "905550000001@s.whatsapp.net".into(),
                new_pn: "905550000002@s.whatsapp.net".into(),
                old_lid: Some("1234@lid".into()),
                new_lid: Some("1234@lid".into()),
            }
        );
        let bare = ContactNumberChanged::builder()
            .old_jid(jid("905550000001@s.whatsapp.net"))
            .new_jid(jid("905550000002@s.whatsapp.net"))
            .timestamp(at)
            .build();
        assert_eq!(
            number_changed(&bare),
            GatewayEvent::IdentityNumberChanged {
                old_pn: "905550000001@s.whatsapp.net".into(),
                new_pn: "905550000002@s.whatsapp.net".into(),
                old_lid: None,
                new_lid: None,
            }
        );
    }

    #[test]
    fn caption_edits_resend_the_media_kind() {
        let edit = caption_edit(MediaKind::Image, "new").unwrap();
        assert_eq!(
            edit.image_message.as_option().unwrap().caption.as_deref(),
            Some("new")
        );
        assert!(caption_edit(MediaKind::Sticker, "x").is_none());
        assert!(is_editable(&MessageBody::Text("a".into())));
        assert!(!is_editable(&MessageBody::Unsupported));
        assert!(!is_editable(&MessageBody::Media {
            kind: MediaKind::Audio,
            caption: None,
            mime: "audio/ogg".into(),
            size: 1,
        }));
    }
}
