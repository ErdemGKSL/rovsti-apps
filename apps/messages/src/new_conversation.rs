//! The new-message entry: type a number, or a name to search the contacts.
//! The matching is pure (`options`); the view only draws the list.

use gpui::{
    Context, Entity, EventEmitter, InteractiveElement, IntoElement, ParentElement, Render,
    SharedString, StatefulInteractiveElement, Styled, TextInputAction, TextInputPurpose, Window,
    div, prelude::*, px,
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

/// Rows shown for an empty or broad query.
const LIMIT: usize = 40;

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

/// What the entry tells the page that hosts it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum NewConversationEvent {
    Back,
    Open(Recipient),
}

impl EventEmitter<NewConversationEvent> for NewConversation {}

/// The options for `query`: the typed number first when it reads as one, then
/// the matching contacts with a number (their default, else their first).
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
    for contact in contacts.search(query) {
        let number = contact
            .numbers
            .iter()
            .find(|n| n.default && !n.raw.trim().is_empty())
            .or_else(|| contact.numbers.iter().find(|n| !n.raw.trim().is_empty()));
        let Some(number) = number else {
            continue;
        };
        let title = if contact.display_name.trim().is_empty() {
            number.raw.clone()
        } else {
            contact.display_name.clone()
        };
        found.push(Recipient {
            title,
            number: number.raw.clone(),
            detail: Some(number.raw.clone()),
        });
        if found.len() >= LIMIT {
            break;
        }
    }
    found
}

pub struct NewConversation {
    backend: Entity<Backend>,
    field: Entity<TextField>,
    seen_edits: u64,
    options: Vec<Recipient>,
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
            // The address book may arrive after the screen opens.
            cx.observe(&backend, |this, _, cx| this.rebuild(cx)),
        ];
        let mut this = Self {
            backend,
            field,
            seen_edits: 0,
            options: Vec::new(),
            _subscriptions: subscriptions,
        };
        this.rebuild(cx);
        this
    }

    fn rebuild(&mut self, cx: &mut Context<Self>) {
        let query = self.field.read(cx).text().to_owned();
        let next = options(&query, self.backend.read(cx).contacts());
        if next != self.options {
            self.options = next;
            cx.notify();
        }
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

        let rows = self.options.iter().enumerate().map(|(index, option)| {
            let chosen = option.clone();
            let mut text = div().flex_1().min_w_0().flex().flex_col().child(
                div()
                    .truncate()
                    .text_size(typography::HEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.label)
                    .child(SharedString::from(option.title.clone())),
            );
            if let Some(detail) = &option.detail {
                text = text.child(
                    div()
                        .truncate()
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(colors.secondary_label)
                        .child(SharedString::from(detail.clone())),
                );
            }
            div()
                .id(("new-option", index))
                .flex()
                .items_center()
                .gap(px(12.0))
                .px(px(16.0))
                .py(px(8.0))
                .border_b(px(0.5))
                .border_color(colors.separator)
                .child(avatar(theme, initials(&option.title), 44.0, colors.accent))
                .child(text)
                .on_click(cx.listener(move |_, _, _, cx| {
                    cx.emit(NewConversationEvent::Open(chosen.clone()));
                }))
        });

        let empty = self.options.is_empty().then(|| {
            div()
                .py(px(32.0))
                .flex()
                .justify_center()
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .child("No matching contacts. Type a full number to message it.")
        });

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
            .child(
                div()
                    .id("new-scroll")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .children(empty)
                    .children(rows),
            )
    }
}

#[cfg(test)]
mod tests {
    use rovsti_core::wire::{WireContact, WireNumber};

    use super::*;

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
        assert_eq!(found.len(), 2);
        assert_eq!(found[0].title, "Ayşe Yılmaz");
        assert_eq!(found[0].detail.as_deref(), Some("+905321234567"));
    }

    #[test]
    fn a_name_searches_and_uses_the_default_number() {
        let found = options("mert", &index());
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].number, "+905559876543");
        assert!(options("zzz", &index()).is_empty());
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
