//! My card and the emergency contacts: the Contacts tab row, the card's menu
//! entries and the Settings page.
//!
//! Who is "me" and who is on the emergency list lives in
//! `rovsti_core::contact_me` (lookup keys saved in `ContactsPrefs`); this file
//! maps it to rows and texts and draws the pages.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, px,
};
use rovsti_core::{
    avatar, calling_code,
    contact_card::ContactCard,
    contact_me,
    contacts_prefs::MAX_EMERGENCY,
    wire::{NameFormat, WireContact},
};
use rovsti_ui::{
    Icon,
    components::{empty_state, glass_icon_button, large_title, list_row, section},
    theme::{self, hue_pair},
    tokens::{Theme, typography},
};

use crate::{ContactsApp, confirm::Confirm, photos::photo_avatar, ui::tap_row};

/// One emergency page row.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct EmergencyRow {
    pub(crate) id: i64,
    pub(crate) key: String,
    pub(crate) name: String,
    /// As stored; this is what gets dialed.
    pub(crate) raw: String,
    /// Grouped for reading.
    pub(crate) display: String,
}

/// The card menu entry for My card.
pub(crate) fn my_card_menu_entry(is_me: bool) -> &'static str {
    if is_me {
        "Remove my card"
    } else {
        "Set as my card"
    }
}

/// The card menu entry for the emergency list.
pub(crate) fn emergency_menu_entry(on_list: bool) -> &'static str {
    if on_list {
        "Remove from emergency contacts"
    } else {
        "Add to emergency contacts"
    }
}

/// The Settings row subtitle.
pub(crate) fn emergency_count_words(n: usize) -> String {
    if n == 0 {
        "None".to_owned()
    } else {
        format!("{n} of {MAX_EMERGENCY}")
    }
}

/// A number's subtitle on the card: its label, then the country of a foreign
/// number ("Mobile · Germany").
pub(crate) fn number_subtitle(label: &str, raw: &str, home_cc: Option<&str>) -> String {
    match calling_code::country_line(raw, home_cc) {
        Some(country) => format!("{label} · {country}"),
        None => label.to_owned(),
    }
}

/// The page rows: the emergency contacts in order, each with its main number.
pub(crate) fn emergency_rows(
    keys: &[String],
    all: &[WireContact],
    format: NameFormat,
) -> Vec<EmergencyRow> {
    contact_me::emergency_list(keys, all)
        .into_iter()
        .filter_map(|c| {
            let card = ContactCard::new(c, format);
            let number = card.primary()?;
            Some(EmergencyRow {
                id: c.id,
                key: c.lookup_key.clone().unwrap_or_default(),
                name: card.name.clone(),
                raw: number.raw.clone(),
                display: number.display.clone(),
            })
        })
        .collect()
}

/// The contacts the Add picker offers: a number, a key, not on the list yet.
pub(crate) fn addable<'a>(
    contacts: impl IntoIterator<Item = &'a WireContact>,
    keys: &[String],
) -> Vec<&'a WireContact> {
    contacts
        .into_iter()
        .filter(|c| {
            let key = c.lookup_key.as_deref().unwrap_or_default();
            !key.is_empty()
                && !keys.iter().any(|k| k == key)
                && c.numbers.iter().any(|n| !n.raw.trim().is_empty())
        })
        .collect()
}

/// Adds each of `picked` in order. Returns the new keys, how many were added
/// and the first reason one could not be (the rest are skipped after "full").
pub(crate) fn add_all(
    keys: &[String],
    picked: &[&WireContact],
) -> (Vec<String>, usize, Option<contact_me::EmergencyError>) {
    let mut keys = keys.to_vec();
    let mut added = 0;
    let mut problem = None;
    for contact in picked {
        let known = contact
            .lookup_key
            .as_deref()
            .is_some_and(|k| keys.iter().any(|x| x == k));
        if known {
            continue;
        }
        match contact_me::toggle_emergency(&keys, contact) {
            Ok((next, true)) => {
                keys = next;
                added += 1;
            }
            Ok(_) => {}
            Err(error) => {
                problem.get_or_insert(error);
            }
        }
    }
    (keys, added, problem)
}

/// The pill after the Add picker.
pub(crate) fn add_summary(added: usize, problem: Option<&contact_me::EmergencyError>) -> String {
    match (added, problem) {
        (0, Some(error)) => error.to_string(),
        (0, None) => "No changes.".to_owned(),
        (1, None) => contact_me::emergency_notice(true).to_owned(),
        (n, None) => format!("Added {n} to emergency contacts"),
        (n, Some(error)) => format!("Added {n}. {error}"),
    }
}

impl ContactsApp {
    /// The contact that is me, if set and still in the book.
    pub(crate) fn my_card_contact(&self) -> Option<&WireContact> {
        let key = self.my_card.as_deref().filter(|k| !k.is_empty())?;
        self.index()?
            .all()
            .find(|c| c.lookup_key.as_deref() == Some(key))
    }

    fn lookup_key_of(&self, id: i64) -> Option<String> {
        self.index()?
            .all()
            .find(|c| c.id == id)
            .and_then(|c| c.lookup_key.clone())
            .filter(|k| !k.is_empty())
    }

    /// Whether contact `id` is my card.
    pub(crate) fn is_my_card(&self, id: i64) -> bool {
        self.my_card_contact().is_some_and(|c| c.id == id)
    }

    /// Whether contact `id` is on the emergency list.
    pub(crate) fn is_emergency(&self, id: i64) -> bool {
        self.lookup_key_of(id)
            .is_some_and(|k| self.emergency.contains(&k))
    }

    /// Rebuilds the emergency page rows from the book.
    pub(crate) fn rebuild_emergency(&mut self) {
        let rows = match self.index() {
            Some(index) => {
                let all: Vec<WireContact> = index.all().cloned().collect();
                emergency_rows(&self.emergency, &all, self.style.format)
            }
            None => Vec::new(),
        };
        self.emergency_rows = rows;
    }

    fn save_me(&mut self, cx: &mut Context<Self>) {
        self.me_touched = true;
        self.rebuild_emergency();
        self.save_prefs(cx);
    }

    // ---- my card ----

    /// The glass row at the top of the Contacts tab.
    pub(crate) fn my_card_entry(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let found = self.my_card_contact();
        let (title, subtitle) = contact_me::my_card_row(found);
        let id = found.map(|c| c.id);
        let row = list_row(theme, None, title, Some(subtitle.into()), None)
            .id("my-card")
            .on_click(cx.listener(move |this, _, _, cx| match id {
                Some(id) => this.open_contact(id, cx),
                None => this.open_picker(crate::picker::PickFor::MyCard, Vec::new(), cx),
            }))
            .into_any_element();
        div()
            .pb(px(8.0))
            .child(section(theme, None, [row]))
            .into_any_element()
    }

    pub(crate) fn set_my_card(&mut self, id: i64, cx: &mut Context<Self>) {
        let Some(key) = self.lookup_key_of(id) else {
            self.notice = Some("This contact has no key yet".into());
            cx.notify();
            return;
        };
        self.my_card = Some(key);
        self.save_me(cx);
        self.notice = Some(contact_me::my_card_notice(true).into());
        cx.notify();
    }

    pub(crate) fn ask_remove_my_card(&mut self, cx: &mut Context<Self>) {
        self.confirm = Some(Confirm::RemoveMyCard);
        cx.notify();
    }

    pub(crate) fn clear_my_card(&mut self, cx: &mut Context<Self>) {
        self.my_card = None;
        self.save_me(cx);
        self.notice = Some(contact_me::my_card_notice(false).into());
        cx.notify();
    }

    /// Done on the My card picker.
    pub(crate) fn finish_my_card(&mut self, id: Option<i64>, cx: &mut Context<Self>) {
        self.close_picker(cx);
        if let Some(id) = id {
            self.set_my_card(id, cx);
        }
    }

    /// The card menu's My card entry.
    pub(crate) fn my_card_menu_action(&mut self, id: i64, cx: &mut Context<Self>) {
        if self.is_my_card(id) {
            self.ask_remove_my_card(cx);
        } else {
            self.set_my_card(id, cx);
        }
    }

    // ---- emergency contacts ----

    pub(crate) fn set_emergency_open(&mut self, open: bool, cx: &mut Context<Self>) {
        self.emergency_open = open;
        self.clear_notice();
        if open {
            self.rebuild_emergency();
        }
        self.page.restart();
        cx.notify();
    }

    /// The card menu's emergency entry: adds, or asks before removing.
    pub(crate) fn emergency_menu_action(&mut self, id: i64, cx: &mut Context<Self>) {
        let Some(contact) = self
            .index()
            .and_then(|index| index.all().find(|c| c.id == id))
        else {
            return;
        };
        let name: SharedString = ContactCard::new(contact, self.style.format).name.into();
        match contact_me::toggle_emergency(&self.emergency, contact) {
            Ok((_, false)) => {
                if let Some(key) = contact.lookup_key.clone() {
                    self.confirm = Some(Confirm::RemoveEmergency { key, name });
                }
            }
            Ok((keys, true)) => {
                self.emergency = keys;
                self.save_me(cx);
                self.notice = Some(contact_me::emergency_notice(true).into());
            }
            Err(error) => self.notice = Some(error.to_string().into()),
        }
        cx.notify();
    }

    pub(crate) fn ask_remove_emergency(
        &mut self,
        key: String,
        name: SharedString,
        cx: &mut Context<Self>,
    ) {
        self.confirm = Some(Confirm::RemoveEmergency { key, name });
        cx.notify();
    }

    pub(crate) fn remove_emergency(&mut self, key: &str, cx: &mut Context<Self>) {
        self.emergency.retain(|k| k != key);
        self.save_me(cx);
        self.notice = Some(contact_me::emergency_notice(false).into());
        cx.notify();
    }

    /// Done on the Add picker.
    pub(crate) fn finish_emergency(&mut self, ids: Vec<i64>, cx: &mut Context<Self>) {
        self.close_picker(cx);
        let (keys, added, problem) = match self.index() {
            Some(index) => {
                let picked: Vec<&WireContact> = ids
                    .iter()
                    .filter_map(|id| index.all().find(|c| c.id == *id))
                    .collect();
                add_all(&self.emergency, &picked)
            }
            None => return,
        };
        if added > 0 {
            self.emergency = keys;
            self.save_me(cx);
        }
        self.notice = Some(add_summary(added, problem.as_ref()).into());
        cx.notify();
    }

    /// "Share emergency info": the list as text through the share sheet.
    fn share_emergency(&mut self, cx: &mut Context<Self>) {
        if self.emergency_rows.is_empty() {
            self.notice = Some("Add an emergency contact first".into());
            cx.notify();
            return;
        }
        let owner = self.my_card_contact().map(|c| c.display_name.clone());
        let entries: Vec<(&str, &str)> = self
            .emergency_rows
            .iter()
            .map(|r| (r.name.as_str(), r.display.as_str()))
            .collect();
        let text = contact_me::emergency_text(owner.as_deref(), &entries);
        self.act(
            "share",
            move || rovsti_android::telecom::share_text(&text),
            cx,
        );
    }

    /// The Settings row.
    pub(crate) fn emergency_entry(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        section(
            theme,
            Some("Emergency".into()),
            [list_row(
                theme,
                None,
                "Emergency contacts",
                None,
                Some(emergency_count_words(self.emergency.len()).into()),
            )
            .id("emergency-open")
            .on_click(cx.listener(|this, _, _, cx| this.set_emergency_open(true, cx)))
            .into_any_element()],
        )
        .into_any_element()
    }

    /// The page: the list with Call and remove, Add, and Share.
    pub(crate) fn emergency_page(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let top = div().px(px(8.0)).pt(px(48.0)).child(
            glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                .id("emergency-back")
                .on_click(cx.listener(|this, _, _, cx| this.set_emergency_open(false, cx))),
        );
        let items = self.emergency_rows.clone();
        let list = if items.is_empty() {
            empty_state(
                theme,
                "No Emergency Contacts",
                "Add up to five people to call quickly or share with others.",
            )
            .into_any_element()
        } else {
            let rows: Vec<AnyElement> = items
                .iter()
                .enumerate()
                .map(|(n, r)| self.emergency_item(theme, r, n, cx))
                .collect();
            section(theme, None, rows).into_any_element()
        };
        let full = self.emergency.len() >= MAX_EMERGENCY;
        let actions = section(
            theme,
            None,
            [
                tap_row(
                    theme,
                    "emergency-add",
                    Some(Icon::LucidePlus),
                    "Add",
                    crate::ui::Tone::Accent,
                )
                .opacity(if full { 0.4 } else { 1.0 })
                .on_click(cx.listener(|this, _, _, cx| {
                    if this.emergency.len() >= MAX_EMERGENCY {
                        this.notice = Some(contact_me::EmergencyError::Full.to_string().into());
                        cx.notify();
                    } else {
                        this.open_picker(crate::picker::PickFor::Emergency, Vec::new(), cx);
                    }
                }))
                .into_any_element(),
                tap_row(
                    theme,
                    "emergency-share",
                    Some(Icon::LucideUpload),
                    "Share emergency info",
                    crate::ui::Tone::Accent,
                )
                .on_click(cx.listener(|this, _, _, cx| this.share_emergency(cx)))
                .into_any_element(),
            ],
        );
        div()
            .flex()
            .flex_col()
            .gap(px(20.0))
            .child(top)
            .child(large_title(theme, "Emergency contacts", None))
            .child(list)
            .child(actions)
            .child(
                div()
                    .px(px(32.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child("The list stays on this phone. Sharing sends it as plain text."),
            )
            .into_any_element()
    }

    fn emergency_item(
        &mut self,
        theme: Theme,
        r: &EmergencyRow,
        n: usize,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let id = r.id;
        let photo_uri = self.photo_uri_of(id).map(str::to_owned);
        if let Some(uri) = photo_uri.as_deref().filter(|_| self.photos.needs(id)) {
            self.want_photos([(id, uri)], cx);
        }
        let picture = self.avatar_picture(id, photo_uri.is_some(), None, cx);
        let mono = avatar::monogram(&r.name)
            .map(String::from)
            .unwrap_or_default();
        let tint = hue_pair(theme, avatar::hue_index(Some(id))).0;
        let leading = photo_avatar(
            theme,
            mono,
            40.0,
            tint,
            picture.as_ref().map(|(image, opacity)| (image, *opacity)),
        )
        .into_any_element();
        let (raw, key, name) = (
            r.raw.clone(),
            r.key.clone(),
            SharedString::from(r.name.clone()),
        );
        let open = list_row(
            theme,
            Some(leading),
            r.name.clone(),
            Some(r.display.clone().into()),
            None,
        )
        .id(("emergency", n))
        .flex_1()
        .on_click(cx.listener(move |this, _, _, cx| {
            this.emergency_open = false;
            this.open_contact(id, cx);
        }));
        let call = glass_icon_button(theme, Icon::LucidePhone, 0.0, false)
            .id(("emergency-call", n))
            .on_click(cx.listener(move |this, _, _, cx| {
                let raw = raw.clone();
                this.act(
                    "call",
                    move || rovsti_android::telecom::place_call(&raw),
                    cx,
                );
            }));
        let remove = glass_icon_button(theme, Icon::LucideTrash2, 0.0, false)
            .id(("emergency-remove", n))
            .on_click(cx.listener(move |this, _, _, cx| {
                this.ask_remove_emergency(key.clone(), name.clone(), cx);
            }));
        div()
            .flex()
            .items_center()
            .gap(px(8.0))
            .pr(px(8.0))
            .child(open)
            .child(call)
            .child(remove)
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::wire::WireNumber;

    fn contact(id: i64, name: &str, key: Option<&str>, number: Option<&str>) -> WireContact {
        WireContact {
            id,
            display_name: name.into(),
            lookup_key: key.map(Into::into),
            numbers: number
                .map(|raw| {
                    vec![WireNumber {
                        raw: raw.into(),
                        label: None,
                        default: false,
                    }]
                })
                .unwrap_or_default(),
            ..WireContact::default()
        }
    }

    #[test]
    fn menu_entries_follow_the_state() {
        assert_eq!(my_card_menu_entry(false), "Set as my card");
        assert_eq!(my_card_menu_entry(true), "Remove my card");
        assert_eq!(emergency_menu_entry(false), "Add to emergency contacts");
        assert_eq!(emergency_menu_entry(true), "Remove from emergency contacts");
    }

    #[test]
    fn count_words() {
        assert_eq!(emergency_count_words(0), "None");
        assert_eq!(emergency_count_words(2), "2 of 5");
    }

    #[test]
    fn rows_keep_key_order_and_drop_missing_contacts() {
        let all = [
            contact(1, "Ayse", Some("a"), Some("+905321112233")),
            contact(2, "Mehmet", Some("m"), Some("+905320000000")),
        ];
        let keys = vec!["m".to_owned(), "gone".to_owned(), "a".to_owned()];
        let rows = emergency_rows(&keys, &all, NameFormat::FirstLast);
        let names: Vec<_> = rows.iter().map(|r| r.name.as_str()).collect();
        assert_eq!(names, ["Mehmet", "Ayse"]);
        assert_eq!(rows[0].raw, "+905320000000");
        assert_eq!(rows[0].key, "m");
        assert!(!rows[0].display.is_empty());
    }

    #[test]
    fn add_filter_needs_a_number_a_key_and_a_new_entry() {
        let all = [
            contact(1, "Has both", Some("a"), Some("+905321112233")),
            contact(2, "No number", Some("b"), None),
            contact(3, "No key", None, Some("+905321112244")),
            contact(4, "Listed", Some("l"), Some("+905321112255")),
            contact(5, "Blank number", Some("c"), Some("  ")),
        ];
        let keys = vec!["l".to_owned()];
        let ids: Vec<_> = addable(all.iter(), &keys).iter().map(|c| c.id).collect();
        assert_eq!(ids, [1]);
    }

    #[test]
    fn adding_many_stops_at_five_and_reports_it() {
        let all: Vec<_> = (0..7)
            .map(|i| contact(i, "P", Some(&format!("k{i}")), Some("+905321112233")))
            .collect();
        let picked: Vec<&WireContact> = all.iter().collect();
        let (keys, added, problem) = add_all(&[], &picked);
        assert_eq!(keys.len(), MAX_EMERGENCY);
        assert_eq!(added, MAX_EMERGENCY);
        assert_eq!(problem, Some(contact_me::EmergencyError::Full));
        assert_eq!(
            add_summary(added, problem.as_ref()),
            "Added 5. Up to 5 emergency contacts"
        );
    }

    #[test]
    fn add_summary_branches() {
        assert_eq!(add_summary(1, None), "Added to emergency contacts");
        assert_eq!(add_summary(3, None), "Added 3 to emergency contacts");
        assert_eq!(add_summary(0, None), "No changes.");
        assert_eq!(
            add_summary(0, Some(&contact_me::EmergencyError::NoNumber)),
            "This contact has no number"
        );
    }

    #[test]
    fn subtitle_names_a_foreign_country_only() {
        assert_eq!(
            number_subtitle("Mobile", "+491701234567", Some("90")),
            "Mobile · Germany"
        );
        assert_eq!(
            number_subtitle("Mobile", "+905321112233", Some("90")),
            "Mobile"
        );
        assert_eq!(number_subtitle("Home", "0212 555 11 22", None), "Home");
    }
}
