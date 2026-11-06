//! The new-message entry: type a number, or a name to search the contacts.
//! The matching is pure (`options`); the view only draws the list, a
//! virtualised `uniform_list` of fixed-height rows.

use std::{ops::Range, rc::Rc, sync::Arc};

use gpui::{
    AnyElement, Context, Entity, EventEmitter, InteractiveElement, IntoElement, ParentElement,
    Render, ScrollStrategy, SharedString, StatefulInteractiveElement, Styled, TextInputAction,
    TextInputPurpose, UniformListScrollHandle, Window, div, prelude::*, px, uniform_list,
};
use rovsti_core::{PhoneNumber, search::ContactIndex};
use rovsti_ui::{
    Icon,
    components::{avatar, glass_icon_button},
    current_theme,
    field::{TextField, field_box},
    theme,
    tokens::typography,
};

use crate::{backend::Backend, conversation::header_bar, inbox::initials};

/// Rows shown for an empty or broad query. The list is virtualised, so this
/// only bounds the work of one search.
const LIMIT: usize = 500;

/// Fixed row height of the virtualised option list.
const ROW_H: f32 = 60.0;

/// Fewest digits that make a typed number worth offering.
const MIN_DIGITS: usize = 3;

/// Someone to message.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Recipient {
    pub title: String,
    pub number: String,
    /// The number under a contact's name; `None` for a typed number.
    pub detail: Option<String>,
}

/// The JID of the first number a WhatsApp lookup found registered.
pub fn registered_jid(users: &[rovsti_wa_proto::WaUser]) -> Option<String> {
    users
        .iter()
        .find(|user| user.on_whatsapp)
        .map(|user| user.jid.clone())
}

/// What the entry tells the page that hosts it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum NewConversationEvent {
    Back,
    Open(Recipient),
}

impl EventEmitter<NewConversationEvent> for NewConversation {}

/// The options for `query`: the typed number first when it reads as one, then
/// the matching contacts, one option per number (the default first).
pub fn options(query: &str, contacts: &ContactIndex) -> Vec<Recipient> {
    let query = query.trim();
    let mut found = Vec::new();
    let digits = query.chars().filter(char::is_ascii_digit).count();
    if digits >= MIN_DIGITS && PhoneNumber::parse(query).is_ok() {
        found.push(Recipient {
            title: query.to_owned(),
            number: query.to_owned(),
            detail: None,
        });
    }
    'contacts: for contact in contacts.search(query) {
        let usable = |n: &&rovsti_core::wire::WireNumber| !n.raw.trim().is_empty();
        // The default number first, then the provider's order.
        let ordered = contact
            .numbers
            .iter()
            .filter(usable)
            .filter(|n| n.default)
            .chain(contact.numbers.iter().filter(usable).filter(|n| !n.default));
        let mut seen: Vec<String> = Vec::new();
        for number in ordered {
            let digits: String = number.raw.chars().filter(char::is_ascii_digit).collect();
            if seen.contains(&digits) {
                continue;
            }
            seen.push(digits);
            let title = if contact.display_name.trim().is_empty() {
                number.raw.clone()
            } else {
                contact.display_name.clone()
            };
            let detail = match number.label.as_deref().map(str::trim) {
                Some(label) if !label.is_empty() => format!("{label} \u{b7} {}", number.raw),
                _ => number.raw.clone(),
            };
            found.push(Recipient {
                title,
                number: number.raw.clone(),
                detail: Some(detail),
            });
            if found.len() >= LIMIT {
                break 'contacts;
            }
        }
    }
    found
}

/// One option row, ready to draw: its text is built once per query.
struct OptionRow {
    recipient: Recipient,
    title: SharedString,
    detail: Option<SharedString>,
    initials: SharedString,
}

impl OptionRow {
    fn new(recipient: Recipient) -> Self {
        Self {
            title: recipient.title.as_str().into(),
            detail: recipient.detail.as_deref().map(SharedString::from),
            initials: initials(&recipient.title).into(),
            recipient,
        }
    }
}

pub struct NewConversation {
    backend: Entity<Backend>,
    field: Entity<TextField>,
    seen_edits: u64,
    /// The options for the current query, ready to draw.
    rows: Rc<[OptionRow]>,
    /// The address book the rows were searched in.
    contacts: Option<Arc<ContactIndex>>,
    scroll: UniformListScrollHandle,
    _subscriptions: Vec<gpui::Subscription>,
}

impl NewConversation {
    pub fn new(backend: Entity<Backend>, cx: &mut Context<Self>) -> Self {
        let field =
            cx.new(|cx| TextField::with_input(TextInputPurpose::Text, TextInputAction::Done, cx));
        let subscriptions = vec![
            cx.observe(&field, |this, field, cx| {
                let edits = field.read(cx).edits;
                if edits != this.seen_edits {
                    this.seen_edits = edits;
                    this.rebuild(cx);
                }
            }),
            // The address book may arrive after the screen opens; other backend
            // changes leave the options alone.
            cx.observe(&backend, |this, backend, cx| {
                let contacts = backend.read(cx).contacts();
                if !this
                    .contacts
                    .as_ref()
                    .is_some_and(|seen| Arc::ptr_eq(seen, contacts))
                {
                    this.rebuild(cx);
                }
            }),
        ];
        let mut this = Self {
            backend,
            field,
            seen_edits: 0,
            rows: Rc::from(Vec::new()),
            contacts: None,
            scroll: UniformListScrollHandle::new(),
            _subscriptions: subscriptions,
        };
        this.rebuild(cx);
        this
    }

    fn rebuild(&mut self, cx: &mut Context<Self>) {
        let query = self.field.read(cx).text().to_owned();
        let contacts = self.backend.read(cx).contacts().clone();
        let next = options(&query, &contacts);
        self.contacts = Some(contacts);
        if !next.iter().eq(self.rows.iter().map(|row| &row.recipient)) {
            self.rows = next.into_iter().map(OptionRow::new).collect();
            self.scroll.scroll_to_item(0, ScrollStrategy::Top);
            cx.notify();
        }
    }

    /// The rows for `range` of the virtualised list.
    fn option_rows(
        &self,
        range: Range<usize>,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Vec<AnyElement> {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        range
            .filter_map(|index| {
                let row = self.rows.get(index)?;
                let chosen = row.recipient.clone();
                let text = div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .truncate()
                            .text_size(typography::HEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(colors.label)
                            .child(row.title.clone()),
                    )
                    .children(row.detail.clone().map(|detail| {
                        div()
                            .truncate()
                            .text_size(typography::SUBHEADLINE.size)
                            .text_color(colors.secondary_label)
                            .child(detail)
                    }));
                Some(
                    div()
                        .id(("new-option", index))
                        // List items are sized to their content: fill the row.
                        .w_full()
                        .h(px(ROW_H))
                        .flex()
                        .items_center()
                        .gap(px(12.0))
                        .px(px(16.0))
                        .border_b(px(0.5))
                        .border_color(colors.separator)
                        .child(avatar(theme, row.initials.clone(), 44.0, colors.accent))
                        .child(text)
                        .on_click(cx.listener(move |_, _, _, cx| {
                            cx.emit(NewConversationEvent::Open(chosen.clone()));
                        }))
                        .into_any_element(),
                )
            })
            .collect()
    }
}

impl Render for NewConversation {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);

        let back = glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
            .id("new-back")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(NewConversationEvent::Back)))
            .into_any_element();
        let header = header_bar(theme, back, "New message".into(), None, None);

        let body = if self.rows.is_empty() {
            div()
                .flex_1()
                .min_h_0()
                .py(px(32.0))
                .flex()
                .justify_center()
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .child("No matching contacts. Type a full number to message it.")
                .into_any_element()
        } else {
            // Contacts grow with the address book: only the rows on screen are built.
            uniform_list(
                "new-options",
                self.rows.len(),
                cx.processor(|this, range: Range<usize>, window, cx| {
                    this.option_rows(range, window, cx)
                }),
            )
            .track_scroll(&self.scroll)
            .flex_1()
            .min_h_0()
            .w_full()
            .into_any_element()
        };

        div()
            .size_full()
            .flex()
            .flex_col()
            .child(header)
            .child(div().flex_none().px(px(12.0)).pb(px(8.0)).child(field_box(
                theme,
                "new-field",
                &self.field,
                "Name or number",
                window,
                cx,
            )))
            .child(body)
    }
}

#[cfg(test)]
mod tests {
    use rovsti_core::wire::{WireContact, WireNumber};

    use super::*;

    #[test]
    fn registered_jid_skips_unregistered() {
        let user = |jid: &str, on_whatsapp| rovsti_wa_proto::WaUser {
            jid: jid.into(),
            on_whatsapp,
            business: false,
            verified_name: None,
        };
        assert_eq!(registered_jid(&[]), None);
        assert_eq!(registered_jid(&[user("1@s.whatsapp.net", false)]), None);
        assert_eq!(
            registered_jid(&[
                user("1@s.whatsapp.net", false),
                user("2@s.whatsapp.net", true)
            ]),
            Some("2@s.whatsapp.net".to_owned())
        );
    }

    fn contact(id: i64, name: &str, numbers: &[(&str, bool)]) -> WireContact {
        WireContact {
            id,
            display_name: name.into(),
            numbers: numbers
                .iter()
                .map(|(raw, default)| WireNumber {
                    raw: (*raw).into(),
                    label: None,
                    default: *default,
                })
                .collect(),
            ..WireContact::default()
        }
    }

    fn index() -> ContactIndex {
        ContactIndex::new(vec![
            contact(1, "Ayşe Yılmaz", &[("+905321234567", false)]),
            contact(
                2,
                "Mert Demir",
                &[("+905550000000", false), ("+905559876543", true)],
            ),
            contact(3, "No Number", &[]),
        ])
    }

    #[test]
    fn an_empty_query_lists_contacts_with_numbers() {
        let found = options("", &index());
        // Ayşe has one number, Mert two.
        assert_eq!(found.len(), 3);
        assert_eq!(found[0].title, "Ayşe Yılmaz");
        assert_eq!(found[0].detail.as_deref(), Some("+905321234567"));
    }

    #[test]
    fn a_name_searches_and_uses_the_default_number() {
        let found = options("mert", &index());
        assert_eq!(found.len(), 2);
        assert_eq!(found[0].number, "+905559876543");
        assert_eq!(found[1].number, "+905550000000");
        assert!(options("zzz", &index()).is_empty());
    }

    #[test]
    fn two_numbers_give_two_labelled_options_and_duplicates_one() {
        let mut c = contact(
            5,
            "Can",
            &[("+905550000001", false), ("+905550000002", true)],
        );
        c.numbers[0].label = Some("Work".into());
        c.numbers.push(WireNumber {
            raw: "+90 555 000 0001".into(),
            label: None,
            default: false,
        });
        let found = options("can", &ContactIndex::new(vec![c]));
        assert_eq!(found.len(), 2);
        assert_eq!(found[0].number, "+905550000002");
        assert_eq!(found[0].detail.as_deref(), Some("+905550000002"));
        assert_eq!(
            found[1].detail.as_deref(),
            Some("Work \u{b7} +905550000001")
        );
    }

    #[test]
    fn a_typed_number_comes_first() {
        let found = options("0532 123", &index());
        assert_eq!(found[0].title, "0532 123");
        assert_eq!(found[0].detail, None);
        // The contact whose number contains it follows.
        assert_eq!(found[1].title, "Ayşe Yılmaz");
        // Too short to be a number: contacts only.
        assert!(options("05", &index()).iter().all(|o| o.detail.is_some()));
        // Letters are not a number.
        assert!(options("mert", &index()).iter().all(|o| o.detail.is_some()));
    }
}
