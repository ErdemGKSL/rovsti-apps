//! Upcoming birthdays and anniversaries: the Favorites tab's short section,
//! its "See all" page, and the Contacts tab's alphabet scrubber.
//!
//! The rules (the next occurrence, the window, the groups, when the scrubber
//! shows, which letter a touch hits) live in `rovsti_core`; this file reads
//! the drafts once per book load and draws.

use std::{collections::HashMap, iter};

use gpui::{
    AnyElement, Context, HapticFeedback, InteractiveElement, IntoElement, MouseButton,
    MouseDownEvent, MouseMoveEvent, ParentElement, SharedString, StatefulInteractiveElement,
    Styled, TouchPhase, Window, div, px,
};
use rovsti_core::{
    avatar,
    contact_dates::{self, Upcoming},
    contacts_view,
};
use rovsti_ui::{
    Icon,
    components::{empty_state, glass_icon_button, large_title, list_row, section},
    glass::{GlassSpec, glass},
    theme::{self, hue_pair},
    tokens::{Theme, shadow, typography},
    touch::drag_surface,
};

use crate::{ContactsApp, backend, photos::photo_avatar, whatsapp::now_ms};

/// The Favorites tab shows this many.
const FAVORITES_SHOWN: usize = 3;
/// The page looks this far ahead.
const PAGE_DAYS: i64 = 365;
/// Offsets for the "Wish" chip ids, so no two chips share one.
const WISH_FAVORITES: usize = 10_000;
const WISH_PAGE: usize = 20_000;

/// Where a row is drawn: its id key, its chip ids and whether it shows the date.
#[derive(Clone, Copy)]
struct Place {
    key: &'static str,
    wish_base: usize,
    with_date: bool,
}

const SECTION: Place = Place {
    key: "upcoming",
    wish_base: WISH_FAVORITES,
    with_date: false,
};
const PAGE: Place = Place {
    key: "upcoming-page",
    wish_base: WISH_PAGE,
    with_date: true,
};

/// Where the scrubber column sits and what it is doing.
#[derive(Default)]
pub(crate) struct Scrub {
    /// The letter index under the finger while dragging.
    pub(crate) current: Option<usize>,
    /// The column's top and height in window pixels, as last drawn.
    pub(crate) top: f32,
    pub(crate) height: f32,
    /// A touch drove the drag: the mouse press that follows the lift is ignored.
    touched: bool,
    dragging: bool,
}

/// The wish a row's chip sends, or `None` without a number.
fn wish_for(u: &Upcoming, numbers: &HashMap<i64, String>) -> Option<(String, String)> {
    let number = numbers.get(&u.contact_id)?;
    Some((
        number.clone(),
        contact_dates::wish_text(&u.name, u.kind, u.turning),
    ))
}

/// "Tomorrow · Turns 31" (the date for rows far away).
fn subtitle(u: &Upcoming, with_date: bool) -> String {
    let mut parts = vec![contact_dates::when_text(u.in_days)];
    if with_date {
        parts.push(u.date_text.clone());
    }
    parts.extend(contact_dates::turning_text(u));
    parts.join(" · ")
}

impl ContactsApp {
    /// Reads every contact on the background executor and keeps the events
    /// within a year. Runs after a book load, never per frame.
    pub(crate) fn load_upcoming(&mut self, cx: &mut Context<Self>) {
        self.upcoming_loads += 1;
        let load = self.upcoming_loads;
        let offset = self.wa.offset_s;
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    backend::pull_full().map(|drafts| {
                        let list = contact_dates::upcoming(&drafts, now_ms(), offset, PAGE_DAYS);
                        let numbers: HashMap<i64, String> = drafts
                            .iter()
                            .filter_map(|d| {
                                let id = d.id?;
                                list.iter().any(|u| u.contact_id == id).then_some(())?;
                                Some((id, d.numbers.first()?.value.clone()))
                            })
                            .collect();
                        (list, numbers)
                    })
                })
                .await;
            this.update(cx, |this, cx| {
                if this.upcoming_loads != load {
                    return;
                }
                // A failed read (no permission yet) leaves the section empty.
                let (list, numbers) = result.unwrap_or_default();
                this.upcoming = list;
                this.upcoming_numbers = numbers;
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn set_upcoming_open(&mut self, open: bool, cx: &mut Context<Self>) {
        self.upcoming_open = open;
        self.clear_notice();
        self.page.restart();
        cx.notify();
    }

    /// One row: avatar, name, when, and the Wish chip when a number exists.
    fn upcoming_row(
        &mut self,
        theme: Theme,
        u: &Upcoming,
        place: Place,
        n: usize,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let Place {
            key,
            wish_base,
            with_date,
        } = place;
        let id = u.contact_id;
        let photo_uri = self.photo_uri_of(id).map(str::to_owned);
        if let Some(uri) = photo_uri.as_deref().filter(|_| self.photos.needs(id)) {
            self.want_photos([(id, uri)], cx);
        }
        let picture = self.avatar_picture(id, photo_uri.is_some(), None, cx);
        let mono = avatar::monogram(&u.name)
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
        let row = list_row(
            theme,
            Some(leading),
            u.name.clone(),
            Some(subtitle(u, with_date).into()),
            None,
        )
        .flex_1()
        .id((key, n))
        .on_click(cx.listener(move |this, _, _, cx| this.open_contact(id, cx)));
        let chip = wish_for(u, &self.upcoming_numbers).map(|(number, text)| {
            Self::wish_chip(theme, wish_base + n).on_click(cx.listener(move |this, _, _, cx| {
                let (number, text) = (number.clone(), text.clone());
                this.act(
                    "send a message",
                    move || rovsti_android::telecom::compose_sms(&number, &text),
                    cx,
                );
            }))
        });
        div()
            .flex()
            .items_center()
            .child(row)
            .children(chip)
            .into_any_element()
    }

    /// The glass "Upcoming" section above the Favorites, `None` when empty.
    pub(crate) fn upcoming_section(
        &mut self,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let shown: Vec<Upcoming> =
            contact_dates::favorites_upcoming(&self.upcoming, FAVORITES_SHOWN).to_vec();
        if shown.is_empty() {
            return None;
        }
        let colors = theme::colors(theme);
        let mut rows: Vec<AnyElement> = shown
            .iter()
            .enumerate()
            .map(|(n, u)| self.upcoming_row(theme, u, SECTION, n, cx))
            .collect();
        rows.push(
            div()
                .id("upcoming-all")
                .flex()
                .items_center()
                .justify_between()
                .min_h(px(44.0))
                .px(px(16.0))
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.accent)
                .child("See all")
                .child(SharedString::from(format!("{}", self.upcoming.len())))
                .on_click(cx.listener(|this, _, _, cx| this.set_upcoming_open(true, cx)))
                .into_any_element(),
        );
        Some(
            div()
                .pb(px(8.0))
                .child(section(theme, Some("Upcoming".into()), rows))
                .into_any_element(),
        )
    }

    /// The full page: the next 365 days under This week, This month, Later.
    pub(crate) fn upcoming_page(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let top = div().px(px(8.0)).pt(px(48.0)).child(
            glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                .id("upcoming-back")
                .on_click(cx.listener(|this, _, _, cx| this.set_upcoming_open(false, cx))),
        );
        let items = self.upcoming.clone();
        let groups = contact_dates::upcoming_groups(&items);
        let body = if groups.is_empty() {
            empty_state(
                theme,
                "No Upcoming Dates",
                "Add a birthday or anniversary to a contact to see it here.",
            )
            .into_any_element()
        } else {
            let mut n = 0;
            div()
                .flex()
                .flex_col()
                .gap(px(8.0))
                .children(groups.into_iter().map(|(title, list)| {
                    let rows: Vec<AnyElement> = list
                        .iter()
                        .map(|u| {
                            n += 1;
                            self.upcoming_row(theme, u, PAGE, n, cx)
                        })
                        .collect();
                    section(theme, Some(title.into()), rows)
                }))
                .into_any_element()
        };
        div()
            .child(top)
            .child(large_title(theme, "Upcoming", None))
            .child(body)
            .into_any_element()
    }

    // The alphabet scrubber.

    /// Rebuilds the scrubber's letters from the Contacts rows.
    pub(crate) fn set_scrub_letters(&mut self) {
        self.row_letters = self
            .rows
            .iter()
            .map(|r| r.letter.chars().next().unwrap_or('#'))
            .collect();
        self.scrub_letters = contacts_view::scrubber_letters(self.row_letters.iter().copied());
        self.scrub.current = None;
    }

    /// Moves to the letter under window `y`: scrolls, ticks once per change.
    fn scrub_to(&mut self, y: f32, window: &Window, cx: &mut Context<Self>) {
        let Some(ix) = contacts_view::letter_at(
            y - self.scrub.top,
            self.scrub.height,
            self.scrub_letters.len(),
        ) else {
            return;
        };
        if self.scrub.current == Some(ix) {
            return;
        }
        self.scrub.current = Some(ix);
        rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
        if let Some(row) = self
            .scrub_letters
            .get(ix)
            .and_then(|&c| contacts_view::first_row_of(&self.row_letters, c))
        {
            // Item 0 is the header; the rows follow.
            self.contacts_list.scroll_to(gpui::ListOffset {
                item_ix: row + 1,
                offset_in_item: px(0.0),
            });
        }
        cx.notify();
    }

    fn scrub_end(&mut self, cx: &mut Context<Self>) {
        if self.scrub.current.take().is_some() {
            cx.notify();
        }
    }

    /// The trailing letter column and, while dragging, the glass bubble.
    pub(crate) fn scrubber_view(
        &mut self,
        theme: Theme,
        top: f32,
        height: f32,
        cx: &mut Context<Self>,
    ) -> Vec<AnyElement> {
        let touch = cx.entity();
        self.scrub.top = top;
        self.scrub.height = height;
        let colors = theme::colors(theme);
        let count = self.scrub_letters.len().max(1);
        let cell = height / count as f32;
        let letters = self.scrub_letters.iter().enumerate().map(|(ix, c)| {
            let on = self.scrub.current == Some(ix);
            div()
                .flex_1()
                .flex()
                .items_center()
                .justify_center()
                .text_size(px(11.0))
                .font_weight(typography::HEADLINE.weight)
                .text_color(if on {
                    colors.accent
                } else {
                    colors.secondary_label
                })
                .child(SharedString::from(c.to_string()))
        });
        let column = div()
            .id("scrubber")
            .absolute()
            .right(px(2.0))
            .top(px(top))
            .h(px(height))
            .w(px(24.0))
            .flex()
            .flex_col()
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, event: &MouseDownEvent, window, cx| {
                    if std::mem::take(&mut this.scrub.touched) {
                        return;
                    }
                    this.scrub_to(f32::from(event.position.y), window, cx);
                }),
            )
            .on_mouse_move(cx.listener(|this, event: &MouseMoveEvent, window, cx| {
                if event.pressed_button == Some(MouseButton::Left) {
                    this.scrub_to(f32::from(event.position.y), window, cx);
                }
            }))
            .on_mouse_up(
                MouseButton::Left,
                cx.listener(|this, _, _, cx| this.scrub_end(cx)),
            )
            .children(letters)
            .child(drag_surface(move |phase, pos, window, cx| {
                touch.update(cx, |this, cx| this.scrub_touch(phase, pos, window, cx));
            }));
        let bubble = self.scrub.current.map(|ix| {
            let letter = self.scrub_letters.get(ix).copied().unwrap_or('#');
            let centre = top + cell * (ix as f32 + 0.5);
            glass(GlassSpec::control(theme))
                .absolute()
                .right(px(48.0))
                .top(px(centre - 32.0))
                .size(px(64.0))
                .rounded_full()
                .shadow(shadow::glass(theme.is_dark()))
                .flex()
                .items_center()
                .justify_center()
                .text_size(px(32.0))
                .font_weight(typography::HEADLINE.weight)
                .text_color(colors.label)
                .child(SharedString::from(letter.to_string()))
        });
        iter::once(column.into_any_element())
            .chain(bubble.map(IntoElement::into_any_element))
            .collect()
    }

    /// The finger's path on the column; the mouse press that follows a lift is
    /// then ignored.
    fn scrub_touch(
        &mut self,
        phase: TouchPhase,
        pos: gpui::Point<gpui::Pixels>,
        window: &Window,
        cx: &mut Context<Self>,
    ) {
        match phase {
            TouchPhase::Started => {
                self.scrub.touched = true;
                self.scrub.dragging = true;
                self.scrub_to(f32::from(pos.y), window, cx);
            }
            TouchPhase::Moved if self.scrub.dragging => {
                self.scrub_to(f32::from(pos.y), window, cx);
            }
            TouchPhase::Moved => {}
            TouchPhase::Ended | TouchPhase::Cancelled => {
                self.scrub.dragging = false;
                self.scrub_end(cx);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::contact_edit::EventKind;

    fn up(in_days: i64, turning: Option<i64>) -> Upcoming {
        Upcoming {
            contact_id: 7,
            name: "Anna Lee".into(),
            kind: EventKind::Birthday,
            in_days,
            date_text: "12 May".into(),
            turning,
        }
    }

    #[test]
    fn subtitles_say_when_and_how_old() {
        assert_eq!(subtitle(&up(1, Some(31)), false), "Tomorrow · Turns 31");
        assert_eq!(subtitle(&up(5, None), true), "In 5 days · 12 May");
    }

    #[test]
    fn the_wish_needs_a_number() {
        let mut numbers = HashMap::new();
        assert!(wish_for(&up(0, None), &numbers).is_none());
        numbers.insert(7, "+4915".to_owned());
        assert_eq!(
            wish_for(&up(0, Some(31)), &numbers),
            Some(("+4915".to_owned(), "Happy 31st birthday, Anna!".to_owned()))
        );
    }
}
