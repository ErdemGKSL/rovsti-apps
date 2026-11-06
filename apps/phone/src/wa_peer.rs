//! WhatsApp people in Phone: what to call a WhatsApp peer and which picture to
//! draw for them.
//!
//! A name is the address book's, then WhatsApp's own (a profile, then the name
//! the call came with), then the number. A picture is the contact's photo,
//! then the WhatsApp profile picture, then the monogram. Pictures come from
//! the shared [`rovsti_wa_ui::WaAvatars`]; this module only says which JID to
//! ask for and reads what is held. Everything here is resolved when a call or
//! a list changes, never per frame.

use std::sync::Arc;

use gpui::{App, Context, RenderImage, SharedString};
use rovsti_core::avatar;

use crate::{PhoneApp, backend::Load, wa_call::dial_number};

/// Where a person's picture may come from, in order.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct CallerPhoto {
    /// The matched contact and the provider URI of its photo.
    pub contact: Option<(i64, SharedString)>,
    /// The WhatsApp JID whose profile picture stands in for a missing photo.
    pub wa_jid: Option<SharedString>,
}

impl CallerPhoto {
    pub(crate) fn is_empty(&self) -> bool {
        self.contact.is_none() && self.wa_jid.is_none()
    }
}

/// A WhatsApp peer as the call screens draw it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct WaCaller {
    pub name: String,
    /// The matched contact, for its hue.
    pub contact_id: Option<i64>,
    pub photo: CallerPhoto,
}

/// The JID pictures and profiles are kept under: a phone JID without its
/// device part (`905551234567:12@s.whatsapp.net` → `905551234567@s.whatsapp.net`),
/// any other JID (`@lid`) as it is.
pub(crate) fn bare_jid(peer: &str) -> String {
    match dial_number(peer) {
        Some(number) => format!("{}@s.whatsapp.net", number.trim_start_matches('+')),
        None => peer.to_owned(),
    }
}

/// The JID a contact is reached at on WhatsApp: its first number with a
/// country code. `None` when no number has one.
pub(crate) fn contact_jid<'a>(numbers: impl IntoIterator<Item = &'a str>) -> Option<SharedString> {
    numbers
        .into_iter()
        .find_map(|n| rovsti_wa_proto::user_jid(n).ok())
        .map(SharedString::from)
}

/// The name for a WhatsApp peer: the contact's, then WhatsApp's profile name,
/// then the label the call came with (a push name or the number).
pub(crate) fn caller_name(
    contact: Option<&str>,
    profile: Option<&str>,
    call_label: &str,
    jid: &str,
) -> String {
    let wa_name = profile
        .map(str::trim)
        .filter(|n| !n.is_empty())
        .or(Some(call_label));
    rovsti_wa_ui::display_name(contact, wa_name, jid)
}

impl PhoneApp {
    /// A WhatsApp instance is linked: only then are pictures and profiles asked for.
    pub(crate) fn wa_linked(&self, cx: &App) -> bool {
        self.whatsapp.read(cx).link().is_some()
    }

    /// Resolves a WhatsApp peer against the address book and WhatsApp's profiles.
    /// `call_label` is what the gateway calls the peer (`WaCallView::peer_label`).
    pub(crate) fn wa_caller(&self, peer: &str, call_label: &str, cx: &App) -> WaCaller {
        let jid = bare_jid(peer);
        let backend = self.backend.read(cx);
        let format = backend.settings().name_format;
        let contacts = backend.contacts();
        let contact = match (&contacts, dial_number(peer)) {
            (Load::Ready(index), Some(number)) => index.match_number(&number),
            _ => None,
        };
        let contact_name = contact.map(|c| rovsti_core::names::display(c, format));
        let avatars = self.wa_avatars.read(cx);
        let name = caller_name(
            contact_name.as_deref(),
            avatars.name(&jid),
            call_label,
            &jid,
        );
        WaCaller {
            name,
            contact_id: contact.map(|c| c.id),
            photo: CallerPhoto {
                contact: contact.and_then(|c| {
                    c.photo_uri
                        .as_deref()
                        .map(|uri| (c.id, SharedString::from(uri.to_owned())))
                }),
                wa_jid: (!jid.is_empty()).then(|| jid.into()),
            },
        }
    }

    /// Asks for the WhatsApp profile of `jid` (its name), once per session.
    pub(crate) fn want_wa_profile(&mut self, jid: &str, cx: &mut Context<Self>) {
        if self.wa_linked(cx) && self.wa_avatars.read(cx).profile(jid).is_none() {
            self.wa_avatars
                .update(cx, |avatars, cx| avatars.want_profile(jid, cx));
        }
    }

    /// Starts loading the picture `photo` names, unless one is already held.
    /// The contact's photo is asked for first; WhatsApp's only when the contact
    /// has none (or it could not be read).
    pub(crate) fn want_caller_photo(&mut self, photo: &CallerPhoto, cx: &mut Context<Self>) {
        if photo.is_empty() || self.caller_photo(photo, cx).is_some() {
            return;
        }
        let local_missing = match &photo.contact {
            Some((id, uri)) => {
                let (id, uri) = (*id, uri.clone());
                self.want_photos(std::iter::once((id, &uri)), cx);
                self.photos.is_missing(id)
            }
            None => true,
        };
        if local_missing
            && let Some(jid) = &photo.wa_jid
            && self.wa_linked(cx)
        {
            self.wa_avatars
                .update(cx, |avatars, cx| avatars.want(jid, None, cx));
        }
    }

    /// The picture to draw for `photo` and its opacity while it fades in:
    /// the contact's photo, else the WhatsApp picture. Cheap: clones an `Arc`.
    pub(crate) fn caller_photo(
        &self,
        photo: &CallerPhoto,
        cx: &App,
    ) -> Option<(Arc<RenderImage>, f32)> {
        photo
            .contact
            .as_ref()
            .and_then(|(id, _)| self.photos.get(*id))
            .map(|(image, opacity)| (Arc::clone(image), opacity))
            .or_else(|| {
                photo
                    .wa_jid
                    .as_ref()
                    .and_then(|jid| self.wa_avatars.read(cx).picture(jid))
            })
    }
}

/// The hue of a WhatsApp peer: the contact's when it is one, none otherwise.
pub(crate) fn caller_hue(contact_id: Option<i64>) -> Option<usize> {
    avatar::avatar_hue(contact_id.map_or(avatar::AvatarKey::Unknown, avatar::AvatarKey::Contact))
}

#[cfg(test)]
mod tests {
    use super::*;

    const J: &str = "905551234567@s.whatsapp.net";

    #[test]
    fn names_come_from_the_contact_then_whatsapp_then_the_call() {
        assert_eq!(caller_name(Some("Ayşe"), Some("ayse"), "A", J), "Ayşe");
        assert_eq!(caller_name(None, Some("ayse"), "A", J), "ayse");
        assert_eq!(caller_name(None, Some("  "), "Push", J), "Push");
        assert_eq!(caller_name(None, None, "+905551234567", J), "+905551234567");
        assert_eq!(caller_name(None, None, "", J), "+905551234567");
    }

    #[test]
    fn device_parts_are_dropped_from_phone_jids() {
        assert_eq!(bare_jid("905551234567:12@s.whatsapp.net"), J);
        assert_eq!(bare_jid(J), J);
        assert_eq!(bare_jid("abc@lid"), "abc@lid");
    }

    #[test]
    fn a_contact_is_reached_at_its_first_international_number() {
        assert_eq!(
            contact_jid(["0532 123", "+90 555 123 45 67"]).as_deref(),
            Some(J)
        );
        assert_eq!(contact_jid(["0532 123 45 67"]), None);
        assert_eq!(contact_jid([]), None);
    }

    #[test]
    fn an_empty_photo_names_nothing() {
        assert!(CallerPhoto::default().is_empty());
        let photo = CallerPhoto {
            wa_jid: Some(J.into()),
            ..CallerPhoto::default()
        };
        assert!(!photo.is_empty());
    }
}
