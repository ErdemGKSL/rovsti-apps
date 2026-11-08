//! WhatsApp on the contact card and in the list (AD-CT6).
//!
//! Contacts never sends the address book to WhatsApp. The list mark comes from
//! the instance's existing chats (`GET /v1/chats`, no lookup). Only when a card
//! opens are that one contact's international numbers looked up, and presence
//! is subscribed for the WhatsApp number. Everything that decides something is
//! a pure function in `rovsti_app_core::contacts::whatsapp`; this is the GPUI
//! half that applies the answers.

use std::collections::HashMap;

use gpui::{Context, SharedString};
use rovsti_app_core::contacts::whatsapp::{
    LookupState, fetch_wa_photo, marked_contacts, wa_chip_index,
};
pub(crate) use rovsti_app_core::contacts::whatsapp::{WaPanel, profile_lines};
use rovsti_core::recents::LocaleTime;
use rovsti_wa_proto::{ChatSummary, GatewayEvent, chat_users};

use crate::ContactsApp;

pub(crate) fn now_ms() -> i64 {
    rovsti_core::clock::now_ms()
}

impl ContactsApp {
    /// Reads the phone's time zone once, for "last seen" lines.
    pub(crate) fn pull_wa_offset(&mut self, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let raw = cx
                .background_executor()
                .spawn(async { rovsti_android::providers::local_time_info() })
                .await;
            if let Some(locale) = raw.ok().and_then(|text| LocaleTime::parse(&text)) {
                this.update(cx, |this, cx| {
                    let changed = this.wa.offset_s != locale.tz_offset_s;
                    this.wa.offset_s = locale.tz_offset_s;
                    this.locale = locale;
                    if changed {
                        // "Today" moves with the zone.
                        this.load_upcoming(cx);
                    }
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
    }

    /// The shared settings entity changed: take its gateway events, and read
    /// the chats again when the instance just connected or asked for a resync.
    pub(crate) fn on_whatsapp_changed(&mut self, cx: &mut Context<Self>) {
        let (events, connected) = self
            .whatsapp
            .update(cx, |wa, _| (wa.take_events(), wa.is_connected()));
        let now = now_ms();
        let mut resync = false;
        for event in &events {
            resync |= matches!(
                event,
                GatewayEvent::Resync | GatewayEvent::ChatMerged { .. }
            );
            self.wa.book.apply(event, now);
            self.avatars
                .update(cx, |avatars, cx| avatars.on_event(event, cx));
        }
        let just_connected = connected && !self.wa.connected;
        self.wa.connected = connected;
        self.refresh_card_wa();
        if resync || just_connected {
            self.refresh_chats(cx);
        } else if connected {
            self.check_whatsapp(cx);
        }
        cx.notify();
    }

    /// Reads the chats (background) and re-marks the list.
    pub(crate) fn refresh_chats(&mut self, cx: &mut Context<Self>) {
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            return;
        };
        self.wa.chats_gen += 1;
        let generation = self.wa.chats_gen;
        cx.spawn(async move |this, cx| {
            let chats = cx
                .background_executor()
                .spawn(async move { gateway.chats() })
                .await;
            this.update(cx, |this, cx| {
                if this.wa.chats_gen != generation {
                    return;
                }
                match chats {
                    Ok(chats) => this.set_chats(&chats, cx),
                    Err(error) => log::warn!("reading the WhatsApp chats failed: {error}"),
                }
            })
            .ok();
        })
        .detach();
    }

    fn set_chats(&mut self, chats: &[ChatSummary], cx: &mut Context<Self>) {
        self.wa.chat_users = chat_users(chats);
        self.refresh_marks();
        self.refresh_card_wa();
        self.check_whatsapp(cx);
        cx.notify();
    }

    /// Recomputes the list marks: once per pull and per chats refresh.
    pub(crate) fn refresh_marks(&mut self) {
        self.wa.marked = match self.index() {
            Some(index) => marked_contacts(index.all(), &self.wa.chat_users),
            None => HashMap::new(),
        };
        self.apply_marks();
    }

    /// Copies the marks onto the built rows.
    pub(crate) fn apply_marks(&mut self) {
        for row in self
            .rows
            .iter_mut()
            .chain(self.favorites.iter_mut())
            .chain(self.group_rows.iter_mut())
        {
            row.set_whatsapp(self.wa.marked.get(&row.vm.id).map(String::as_str));
        }
    }

    /// The WhatsApp JID of the open card, when one is known (a chat, or a
    /// number the lookup found registered).
    pub(crate) fn card_jid(&self) -> Option<String> {
        let card = self.open.as_ref()?;
        let numbers: Vec<String> = card.numbers.iter().map(|n| n.raw.clone()).collect();
        self.wa.jid(card.id, &numbers)
    }

    /// Recomputes the per-number WhatsApp chips of the open card. Called when
    /// the card opens and when chats or the lookup change, never per frame.
    pub(crate) fn refresh_card_wa(&mut self) {
        let jids: Vec<Option<String>> = match &self.open {
            Some(card) if self.wa.connected => card
                .numbers
                .iter()
                .map(|n| self.wa.jid_for_number(card.id, &n.raw))
                .collect(),
            _ => Vec::new(),
        };
        let keep = wa_chip_index(&jids);
        self.card_wa_numbers = jids
            .into_iter()
            .zip(keep)
            .map(|(jid, keep)| jid.filter(|_| keep))
            .collect();
    }

    /// For the open card: ask for the WhatsApp picture (shown when the
    /// contact has no photo of its own) and the WhatsApp profile.
    pub(crate) fn request_wa_card(&mut self, cx: &mut Context<Self>) {
        if self.editor.is_some() {
            return;
        }
        let Some(jid) = self.card_jid() else { return };
        let Some(id) = self.open.as_ref().map(|card| card.id) else {
            return;
        };
        // The section offers the picture even over a photo of the contact's own.
        self.want_wa_picture(id, false, &jid, cx);
        self.avatars
            .update(cx, |avatars, cx| avatars.want_profile(&jid, cx));
    }

    /// Saves the WhatsApp picture of `jid` as the photo of contact `id`: the
    /// large picture is fetched, written to the data folder and handed to
    /// the same path a picked photo takes (Android deletes the file after).
    pub(crate) fn use_wa_photo(&mut self, id: i64, jid: String, cx: &mut Context<Self>) {
        // A second tap while the first is fetching or saving does nothing.
        if self.wa_photo_busy {
            return;
        }
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            self.notice = Some("Connect WhatsApp to use the photo.".into());
            cx.notify();
            return;
        };
        let Some(dir) = self.prefs_dir.clone() else {
            self.notice = Some("Can't change the photo right now.".into());
            cx.notify();
            return;
        };
        self.wa_photo_busy = true;
        self.notice = Some("Getting the WhatsApp photo…".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let saved = cx
                .background_executor()
                .spawn(async move { fetch_wa_photo(&gateway, &jid, &dir, id) })
                .await;
            this.update(cx, |this, cx| match saved {
                // `photo_changed` ends the busy state once Android answers.
                Ok(path) => this.apply_photo(id, path, cx),
                Err(words) => {
                    this.wa_photo_busy = false;
                    this.notice = Some(format!("Can't use the WhatsApp photo: {words}.").into());
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    /// For the open card: look the contact up when needed and subscribe
    /// presence once. Sends at most this one contact's numbers.
    pub(crate) fn check_whatsapp(&mut self, cx: &mut Context<Self>) {
        if !self.wa.connected {
            return;
        }
        let Some(card) = &self.open else { return };
        let id = card.id;
        let numbers: Vec<String> = card.numbers.iter().map(|n| n.raw.clone()).collect();
        let Some(gateway) = self.whatsapp.read(cx).gateway() else {
            return;
        };
        let jids = self.wa.lookup_request(id, &numbers);
        if jids.is_empty() {
            self.subscribe_presence(id, &numbers, &gateway, cx);
            return;
        }
        self.wa.lookups.insert(id, LookupState::Checking);
        let lookup = gateway.clone();
        cx.spawn(async move |this, cx| {
            let answer = cx
                .background_executor()
                .spawn(async move { lookup.lookup_users(&jids) })
                .await;
            this.update(cx, |this, cx| {
                let state = match answer {
                    Ok(users) => LookupState::Done(users),
                    Err(error) => {
                        log::warn!("the WhatsApp lookup failed: {error}");
                        LookupState::Failed
                    }
                };
                this.wa.lookups.insert(id, state);
                this.refresh_card_wa();
                if let Some(card) = this.open.as_ref().filter(|c| c.id == id) {
                    let numbers: Vec<String> = card.numbers.iter().map(|n| n.raw.clone()).collect();
                    this.subscribe_presence(id, &numbers, &gateway, cx);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn subscribe_presence(
        &mut self,
        id: i64,
        numbers: &[String],
        gateway: &rovsti_wa_client::Gateway,
        cx: &mut Context<Self>,
    ) {
        let Some(jid) = self.wa.jid(id, numbers) else {
            return;
        };
        if !self.wa.take_subscription(&jid) {
            return;
        }
        let gateway = gateway.clone();
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = gateway.subscribe_presence(&jid) {
                    log::warn!("subscribing to WhatsApp presence failed: {error}");
                }
            })
            .detach();
    }

    /// Hands a chat or a call to Messages or Phone.
    pub(crate) fn wa_handoff(
        &mut self,
        call: bool,
        jid: String,
        name: String,
        cx: &mut Context<Self>,
    ) {
        self.clear_notice();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    if call {
                        rovsti_android::handoff::wa_call(&jid, &name)
                    } else {
                        rovsti_android::handoff::wa_chat(&jid, &name)
                    }
                })
                .await;
            let notice: Option<SharedString> = match result {
                Ok(true) => None,
                Ok(false) if call => Some("Install Rovsti Phone to call on WhatsApp".into()),
                Ok(false) => Some("Install Rovsti Messages to chat on WhatsApp".into()),
                Err(error) => {
                    let what = if call { "call" } else { "chat" };
                    let words = crate::backend::android_words(&error);
                    Some(format!("Can't {what} on WhatsApp: {words}.").into())
                }
            };
            this.update(cx, |this, cx| {
                if let Some(n) = notice {
                    this.notice = Some(n);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}
