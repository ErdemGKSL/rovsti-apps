//! The "Recently added" row on the Contacts tab and its page.
//!
//! Which contacts count and how "when" reads live in
//! `rovsti_core::contacts_view` (`recent`, `recent_when`, `show_recent`); the
//! rows are built once per book pull and only drawn per frame.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, prelude::FluentBuilder, px,
};
use rovsti_core::{
    avatar,
    contact_card::ContactCard,
    contact_recent::{self, AddedMark, RecentTab, VIEWED_KEEP},
    contacts_view,
    wire::WireContact,
};
use rovsti_ui::{
    Icon,
    components::{empty_state, flat_glass, glass_icon_button, large_title, list_row, section},
    theme::{self, hue_pair},
    tokens::{Theme, metrics, typography},
};

use crate::{ContactsApp, confirm::Confirm, photos::photo_avatar};

/// The page looks this far back.
const WITHIN_DAYS: u16 = 30;
/// The page lists at most this many.
const MAX_ROWS: usize = 50;

/// One page row: who and when they changed.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct RecentRow {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) when: String,
}

/// The rows of both tabs.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct RecentLists {
    pub(crate) added: Vec<RecentRow>,
    pub(crate) edited: Vec<RecentRow>,
}

impl RecentLists {
    /// The rows the tab shows.
    pub(crate) fn rows(&self, tab: RecentTab) -> &[RecentRow] {
        match tab {
            RecentTab::Added => &self.added,
            RecentTab::Edited => &self.edited,
        }
    }
}

/// The rows for the contacts, newest first, per tab.
pub(crate) fn build<'a>(
    contacts: impl Iterator<Item = &'a WireContact>,
    marks: &[AddedMark],
    now: i64,
    tz_offset_s: i32,
) -> RecentLists {
    let all: Vec<WireContact> = contacts.cloned().collect();
    let added = contact_recent::added(&all, marks)
        .into_iter()
        .map(|(c, ms)| RecentRow {
            id: c.id,
            name: c.display_name.clone(),
            when: contacts_view::recent_when(ms, now, tz_offset_s),
        })
        .collect();
    let edited = contact_recent::edited(&all, marks, now, WITHIN_DAYS, MAX_ROWS)
        .into_iter()
        .map(|c| RecentRow {
            id: c.id,
            name: c.display_name.clone(),
            when: contacts_view::recent_when(c.updated_ms.unwrap_or(now), now, tz_offset_s),
        })
        .collect();
    RecentLists { added, edited }
}

/// Whether the Recently viewed section shows: not while searching, and only
/// with something to list.
pub(crate) fn viewed_visible(query: &str, count: usize) -> bool {
    query.trim().is_empty() && count > 0
}

/// The pill after Clear.
pub(crate) fn viewed_cleared_notice() -> &'static str {
    "Recently viewed cleared"
}

impl ContactsApp {
    /// The contacts of the viewed list that are still in the book and not
    /// waiting to be deleted, newest first.
    fn viewed_contacts(&self) -> Vec<WireContact> {
        let Some(index) = self.index() else {
            return Vec::new();
        };
        if self.viewed.is_empty() {
            return Vec::new();
        }
        let hidden = self
            .pending_delete
            .as_ref()
            .map(|p| p.ids.as_slice())
            .unwrap_or_default();
        let wanted: Vec<WireContact> = index
            .all()
            .filter(|c| {
                c.lookup_key
                    .as_deref()
                    .is_some_and(|k| self.viewed.iter().any(|v| v == k))
            })
            .cloned()
            .collect();
        contact_recent::viewed(&self.viewed, &wanted, hidden)
            .into_iter()
            .take(VIEWED_KEEP)
            .cloned()
            .collect()
    }

    /// Opening a card: puts the contact first in Recently viewed.
    pub(crate) fn note_viewed_contact(&mut self, id: i64, cx: &mut Context<Self>) {
        let Some(key) = self
            .index()
            .and_then(|index| index.all().find(|c| c.id == id))
            .and_then(|c| c.lookup_key.clone())
            .filter(|k| !k.is_empty())
        else {
            return;
        };
        let next = contact_recent::note_viewed(&self.viewed, &key);
        if next != self.viewed {
            self.viewed = next;
            self.save_prefs(cx);
        }
    }

    /// A deleted contact leaves Recently viewed.
    pub(crate) fn forget_viewed_contacts(&mut self, ids: &[i64], cx: &mut Context<Self>) {
        let keys: Vec<String> = self
            .index()
            .map(|index| {
                index
                    .all()
                    .filter(|c| ids.contains(&c.id))
                    .filter_map(|c| c.lookup_key.clone())
                    .collect()
            })
            .unwrap_or_default();
        let next = keys.iter().fold(self.viewed.clone(), |acc, key| {
            contact_recent::forget_viewed(&acc, key)
        });
        if next != self.viewed {
            self.viewed = next;
            self.save_prefs(cx);
        }
    }

    /// The Clear button was confirmed.
    pub(crate) fn clear_viewed(&mut self, cx: &mut Context<Self>) {
        self.viewed.clear();
        self.save_prefs(cx);
        self.notice = Some(viewed_cleared_notice().into());
        cx.notify();
    }

    /// The Recently viewed section of the Contacts tab: a header with Clear,
    /// then one compact row per contact. `None` while searching or empty.
    pub(crate) fn viewed_entry(
        &mut self,
        theme: Theme,
        query: &str,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let contacts = self.viewed_contacts();
        if !viewed_visible(query, contacts.len()) {
            return None;
        }
        let colors = theme::colors(theme);
        let format = self.style.format;
        let mut rows: Vec<AnyElement> = Vec::with_capacity(contacts.len());
        for (n, contact) in contacts.iter().enumerate() {
            let id = contact.id;
            let has_photo = contact.photo_uri.is_some();
            if let Some(uri) = contact
                .photo_uri
                .as_deref()
                .filter(|_| self.photos.needs(id))
            {
                self.want_photos([(id, uri)], cx);
            }
            let name: SharedString = ContactCard::new(contact, format).name.into();
            let picture = self.avatar_picture(id, has_photo, None, cx);
            let mono = avatar::monogram(&name)
                .map(String::from)
                .unwrap_or_default();
            let tint = hue_pair(theme, avatar::hue_index(Some(id))).0;
            let leading = photo_avatar(
                theme,
                mono,
                32.0,
                tint,
                picture.as_ref().map(|(image, opacity)| (image, *opacity)),
            )
            .into_any_element();
            rows.push(
                list_row(theme, Some(leading), name, None, None)
                    .id(("viewed", n))
                    .on_click(cx.listener(move |this, _, _, cx| this.open_contact(id, cx)))
                    .on_long_press(cx.listener(move |this, _, _, cx| this.open_quick_for(id, cx)))
                    .into_any_element(),
            );
        }
        let clear = div()
            .id("viewed-clear")
            .min_h(px(32.0))
            .flex()
            .items_center()
            .text_size(typography::FOOTNOTE.size)
            .text_color(colors.accent)
            .child("Clear")
            .on_click(cx.listener(|this, _, _, cx| {
                this.confirm = Some(Confirm::ClearViewed);
                cx.notify();
            }));
        let header = div()
            .flex()
            .items_center()
            .justify_between()
            .px(metrics::ROW_INSET)
            .pt(px(4.0))
            .child(
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.secondary_label)
                    .child("Recently viewed"),
            )
            .child(clear);
        Some(
            div()
                .pb(px(8.0))
                .flex()
                .flex_col()
                .child(header)
                .child(section(theme, None, rows))
                .into_any_element(),
        )
    }

    pub(crate) fn set_recent_open(&mut self, open: bool, cx: &mut Context<Self>) {
        self.recent_open = open;
        self.clear_notice();
        self.page.restart();
        cx.notify();
    }

    /// The glass "Recently added" row under the search field.
    pub(crate) fn recent_entry(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let (title, count) =
            contact_recent::entry_title(self.recent.added.len(), self.recent.edited.len());
        let row = div()
            .id("recent-all")
            .flex()
            .items_center()
            .justify_between()
            .min_h(px(44.0))
            .px(px(16.0))
            .text_size(typography::SUBHEADLINE.size)
            .text_color(colors.accent)
            .child(title)
            .child(SharedString::from(count))
            .on_click(cx.listener(|this, _, _, cx| this.set_recent_open(true, cx)))
            .into_any_element();
        div()
            .pb(px(8.0))
            .child(section(theme, None, [row]))
            .into_any_element()
    }

    /// Switches the tab; it is kept for the session.
    fn set_recent_tab(&mut self, tab: RecentTab, cx: &mut Context<Self>) {
        if self.recent_tab != tab {
            self.recent_tab = tab;
            self.page.restart();
            cx.notify();
        }
    }

    /// The page: the last 30 days, newest first, per tab.
    pub(crate) fn recent_page(
        &mut self,
        window: &gpui::Window,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let top = div().px(px(8.0)).pt(px(48.0)).child(
            glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                .id("recent-back")
                .on_click(cx.listener(|this, _, _, cx| this.set_recent_open(false, cx))),
        );
        let tab = self.recent_tab;
        let items = self.recent.rows(tab).to_vec();
        let body = if items.is_empty() {
            let (title, text) = tab.empty();
            empty_state(theme, title, text).into_any_element()
        } else {
            let rows: Vec<AnyElement> = items
                .iter()
                .enumerate()
                .map(|(n, r)| self.recent_item(theme, r, n, cx))
                .collect();
            div()
                .flex()
                .flex_col()
                .child(section(theme, None, rows))
                .into_any_element()
        };
        let tabs = self.recent_tabs(theme, cx);
        div()
            .child(top)
            .child(large_title(window, theme, "Recent", None))
            .child(tabs)
            .child(body)
            .into_any_element()
    }

    /// The glass segmented control: Added and Edited.
    fn recent_tabs(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let segments = RecentTab::ALL.into_iter().enumerate().map(|(n, tab)| {
            let on = tab == self.recent_tab;
            div()
                .id(("recent-tab", n))
                .flex_1()
                .h(px(32.0))
                .flex()
                .items_center()
                .justify_center()
                .rounded_full()
                .text_size(typography::SUBHEADLINE.size)
                .text_color(if on {
                    colors.accent
                } else {
                    colors.secondary_label
                })
                .when(on, |d| d.bg(colors.accent.opacity(0.16)))
                .child(tab.label())
                .on_click(cx.listener(move |this, _, _, cx| this.set_recent_tab(tab, cx)))
        });
        div()
            .px(px(16.0))
            .pb(px(12.0))
            .child(
                flat_glass(theme, None)
                    .rounded_full()
                    .p(px(3.0))
                    .flex()
                    .children(segments),
            )
            .into_any_element()
    }

    fn recent_item(
        &mut self,
        theme: Theme,
        r: &RecentRow,
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
        list_row(
            theme,
            Some(leading),
            r.name.clone(),
            Some(r.when.clone().into()),
            None,
        )
        .id(("recent", n))
        .on_click(cx.listener(move |this, _, _, cx| {
            this.recent_open = false;
            this.open_contact(id, cx);
        }))
        .on_long_press(cx.listener(move |this, _, _, cx| this.open_quick_for(id, cx)))
        .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_700_000_000_000;
    const DAY: i64 = 86_400_000;

    fn contact(id: i64, name: &str, updated: Option<i64>) -> WireContact {
        WireContact {
            id,
            display_name: name.into(),
            updated_ms: updated,
            ..WireContact::default()
        }
    }

    #[test]
    fn tabs_map_to_their_rows() {
        let all = [
            contact(1, "Old", Some(NOW - 40 * DAY)),
            contact(2, "Edited", Some(NOW - 3 * DAY)),
            contact(3, "Fresh", Some(NOW - 1000)),
        ];
        let marks = [AddedMark {
            id: 3,
            ms: NOW - 1000,
        }];
        let lists = build(all.iter(), &marks, NOW, 0);
        let names =
            |tab| -> Vec<String> { lists.rows(tab).iter().map(|r| r.name.clone()).collect() };
        assert_eq!(names(RecentTab::Added), ["Fresh"]);
        assert_eq!(names(RecentTab::Edited), ["Edited"]);
        assert_eq!(lists.rows(RecentTab::Added)[0].when, "Today");
        assert_eq!(RecentTab::ALL.map(RecentTab::label), ["Added", "Edited"]);
    }

    #[test]
    fn empty_tabs_say_which_list_is_empty() {
        let lists = build([].iter(), &[], NOW, 0);
        assert!(lists.rows(RecentTab::Added).is_empty());
        assert_eq!(
            RecentTab::Added.empty().1,
            "No contacts added in the last 30 days"
        );
        assert_eq!(
            RecentTab::Edited.empty().1,
            "No contacts edited in the last 30 days"
        );
    }

    #[test]
    fn rows_are_newest_first_within_thirty_days() {
        let all = [
            contact(1, "Old", Some(NOW - 40 * DAY)),
            contact(2, "Mid", Some(NOW - 3 * DAY)),
            contact(3, "New", Some(NOW - 1000)),
            contact(4, "Never", None),
        ];
        let rows = build(all.iter(), &[], NOW, 0).edited;
        let names: Vec<_> = rows.iter().map(|r| r.name.as_str()).collect();
        assert_eq!(names, ["New", "Mid"]);
        assert_eq!(rows[0].when, "Today");
        assert_eq!(rows[1].when, "3 days ago");
    }

    #[test]
    fn viewed_section_hides_when_empty_or_searching() {
        assert!(viewed_visible("", 3));
        assert!(viewed_visible("   ", 1));
        assert!(!viewed_visible("", 0));
        assert!(!viewed_visible("ay", 3));
    }

    #[test]
    fn clearing_says_so() {
        assert_eq!(viewed_cleared_notice(), "Recently viewed cleared");
    }

    #[test]
    fn rows_are_capped() {
        let all: Vec<_> = (0..80)
            .map(|i| contact(i, "A", Some(NOW - i * 1000)))
            .collect();
        assert_eq!(build(all.iter(), &[], NOW, 0).edited.len(), MAX_ROWS);
    }
}
