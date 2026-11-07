//! The "Recently added" row on the Contacts tab and its page.
//!
//! Which contacts count and how "when" reads live in
//! `rovsti_core::contacts_view` (`recent`, `recent_when`, `show_recent`); the
//! rows are built once per book pull and only drawn per frame.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, px,
};
use rovsti_core::{avatar, contacts_view, wire::WireContact};
use rovsti_ui::{
    Icon,
    components::{empty_state, glass_icon_button, large_title, list_row, section},
    theme::{self, hue_pair},
    tokens::{Theme, typography},
};

use crate::{ContactsApp, photos::photo_avatar};

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

/// The rows for the contacts, newest first.
pub(crate) fn build<'a>(
    contacts: impl Iterator<Item = &'a WireContact>,
    now: i64,
    tz_offset_s: i32,
) -> Vec<RecentRow> {
    let all: Vec<WireContact> = contacts.cloned().collect();
    contacts_view::recent(&all, now, WITHIN_DAYS, MAX_ROWS)
        .into_iter()
        .map(|c| RecentRow {
            id: c.id,
            name: c.display_name.clone(),
            when: contacts_view::recent_when(c.updated_ms.unwrap_or(now), now, tz_offset_s),
        })
        .collect()
}

impl ContactsApp {
    pub(crate) fn set_recent_open(&mut self, open: bool, cx: &mut Context<Self>) {
        self.recent_open = open;
        self.clear_notice();
        self.page.restart();
        cx.notify();
    }

    /// The glass "Recently added" row under the search field.
    pub(crate) fn recent_entry(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let row = div()
            .id("recent-all")
            .flex()
            .items_center()
            .justify_between()
            .min_h(px(44.0))
            .px(px(16.0))
            .text_size(typography::SUBHEADLINE.size)
            .text_color(colors.accent)
            .child("Recently added")
            .child(SharedString::from(self.recent.len().to_string()))
            .on_click(cx.listener(|this, _, _, cx| this.set_recent_open(true, cx)))
            .into_any_element();
        div()
            .pb(px(8.0))
            .child(section(theme, None, [row]))
            .into_any_element()
    }

    /// The page: the last 30 days, newest first.
    pub(crate) fn recent_page(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let top = div().px(px(8.0)).pt(px(48.0)).child(
            glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                .id("recent-back")
                .on_click(cx.listener(|this, _, _, cx| this.set_recent_open(false, cx))),
        );
        let items = self.recent.clone();
        let body = if items.is_empty() {
            empty_state(
                theme,
                "Nothing New",
                "Contacts you add or change show up here for 30 days.",
            )
            .into_any_element()
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
        div()
            .child(top)
            .child(large_title(theme, "Recently added", None))
            .child(body)
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
    fn rows_are_newest_first_within_thirty_days() {
        let all = [
            contact(1, "Old", Some(NOW - 40 * DAY)),
            contact(2, "Mid", Some(NOW - 3 * DAY)),
            contact(3, "New", Some(NOW - 1000)),
            contact(4, "Never", None),
        ];
        let rows = build(all.iter(), NOW, 0);
        let names: Vec<_> = rows.iter().map(|r| r.name.as_str()).collect();
        assert_eq!(names, ["New", "Mid"]);
        assert_eq!(rows[0].when, "Today");
        assert_eq!(rows[1].when, "3 days ago");
    }

    #[test]
    fn rows_are_capped() {
        let all: Vec<_> = (0..80)
            .map(|i| contact(i, "A", Some(NOW - i * 1000)))
            .collect();
        assert_eq!(build(all.iter(), NOW, 0).len(), MAX_ROWS);
    }
}
