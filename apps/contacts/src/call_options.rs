//! The card's "Calls" section (ringtone and "send calls to voicemail"), the
//! Home screen shortcut and the Settings "Contacts" summary page.
//!
//! What the texts say lives in `rovsti_core::contact_options` and
//! `rovsti_core::contact_stats`; the provider is reached through `backend`.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, px,
};
use rovsti_core::{
    contact_options::{self, ContactOptions},
    contact_stats::{self, Summary},
};
use rovsti_ui::{
    Icon,
    components::{empty_state, glass_icon_button, large_title, list_row, section},
    theme,
    tokens::{Theme, typography},
};
use serde::Deserialize;

use crate::{
    ContactsApp, backend,
    confirm::Confirm,
    ui::{Tone, tap_row},
};

/// What the card knows about the open contact's call options.
#[derive(Debug, Default)]
pub(crate) enum CardOptions {
    #[default]
    Idle,
    Loading(i64),
    Ready(i64, ContactOptions),
    /// The provider could not be read (desktop, or a denied permission).
    Failed,
}

impl CardOptions {
    fn ready(&self, id: i64) -> Option<&ContactOptions> {
        match self {
            Self::Ready(of, options) if *of == id => Some(options),
            _ => None,
        }
    }

    fn loading(&self, id: i64) -> bool {
        matches!(self, Self::Loading(of) if *of == id)
    }
}

/// The summary page's state.
#[derive(Debug, Default)]
pub(crate) enum SummaryState {
    #[default]
    Loading,
    Ready(Summary),
    Failed(String),
}

/// The answer of the system ringtone picker.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum Picked {
    Cancelled,
    /// `None` default, `Some("")` silent.
    Chosen(Option<String>),
}

#[derive(Deserialize)]
struct PickedJson {
    #[serde(default)]
    uri: Option<String>,
    #[serde(default)]
    cancelled: bool,
}

/// Reads the `ringtone_picked` payload `{uri, cancelled}`.
pub(crate) fn parse_picked(json: &str) -> Option<Picked> {
    let p: PickedJson = serde_json::from_str(json).ok()?;
    Some(if p.cancelled {
        Picked::Cancelled
    } else {
        Picked::Chosen(p.uri)
    })
}

/// The card to ask before flipping the switch: only turning it on asks.
pub(crate) fn voicemail_toggle(
    current: bool,
    wanted: bool,
    id: i64,
    name: &str,
) -> Option<Confirm> {
    (wanted && !current).then(|| Confirm::Voicemail {
        id,
        name: name.to_owned().into(),
    })
}

type Lines = Vec<(String, String)>;

/// The summary page rows: the overview counts, then one row per account.
pub(crate) fn summary_sections(s: &Summary) -> (Lines, Lines) {
    let mut rows = contact_stats::rows(s);
    let accounts = rows.split_off(rows.len() - s.accounts.len());
    (rows, accounts)
}

impl ContactsApp {
    /// Reads the open contact's options in the background.
    pub(crate) fn load_card_options(&mut self, id: i64, cx: &mut Context<Self>) {
        self.card_options = CardOptions::Loading(id);
        cx.spawn(async move |this, cx| {
            let (options, can_pin) = cx
                .background_executor()
                .spawn(async move { (backend::options_of(id), backend::can_pin()) })
                .await;
            this.update(cx, |this, cx| {
                this.can_pin = can_pin;
                if this.card_options.loading(id) {
                    this.card_options = match options {
                        Ok(options) => CardOptions::Ready(id, options),
                        Err(_) => CardOptions::Failed,
                    };
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// "Ringtone": opens the system picker; the answer is `ringtone_picked`.
    fn pick_ringtone(&mut self, id: i64, cx: &mut Context<Self>) {
        let current = self
            .card_options
            .ready(id)
            .and_then(|o| o.ringtone_uri.clone());
        self.clear_notice();
        match backend::pick_ringtone(current.as_deref()) {
            Ok(()) => self.ringtone_for = Some(id),
            Err(words) => self.notice = Some(format!("Can't change the ringtone: {words}.").into()),
        }
        cx.notify();
    }

    /// The picker answered.
    pub(crate) fn ringtone_picked(&mut self, json: &str, cx: &mut Context<Self>) {
        let Some(picked) = parse_picked(json) else {
            log::warn!("bad ringtone_picked: {json}");
            return;
        };
        let Some(id) = self.ringtone_for.take() else {
            return;
        };
        let Picked::Chosen(uri) = picked else {
            return;
        };
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    backend::set_ringtone(id, uri.as_deref())?;
                    backend::options_of(id)
                })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(options) => {
                        this.notice = Some(contact_options::ringtone_notice(&options).into());
                        this.card_options = CardOptions::Ready(id, options);
                    }
                    Err(words) => {
                        this.notice = Some(format!("Can't change the ringtone: {words}.").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// The switch: turning it on asks first, turning it off is direct.
    fn toggle_voicemail(&mut self, id: i64, name: &str, cx: &mut Context<Self>) {
        let Some(current) = self.card_options.ready(id).map(|o| o.to_voicemail) else {
            self.notice = Some("Can't read this contact's call options.".into());
            cx.notify();
            return;
        };
        self.clear_notice();
        match voicemail_toggle(current, !current, id, name) {
            Some(confirm) => self.confirm = Some(confirm),
            None => self.apply_voicemail(id, !current, cx),
        }
        cx.notify();
    }

    pub(crate) fn apply_voicemail(&mut self, id: i64, on: bool, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::set_voicemail(id, on) })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(()) => {
                        if let CardOptions::Ready(of, options) = &mut this.card_options
                            && *of == id
                        {
                            options.to_voicemail = on;
                        }
                        this.notice = Some(contact_options::voicemail_notice(on).into());
                    }
                    Err(words) => {
                        this.notice = Some(format!("Can't change voicemail: {words}.").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Asks the launcher to pin a shortcut that opens the contact's card.
    pub(crate) fn pin_home(&mut self, id: i64, cx: &mut Context<Self>) {
        let Some(contact) = self.index().and_then(|i| i.all().find(|c| c.id == id)) else {
            self.notice = Some("Can't find this contact.".into());
            cx.notify();
            return;
        };
        let spec = contact_options::shortcut(
            id,
            contact.lookup_key.as_deref(),
            &contact.display_name,
            None,
        );
        self.clear_notice();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::pin_shortcut(&spec) })
                .await;
            this.update(cx, |this, cx| {
                this.notice = Some(match result {
                    Ok(ok) => contact_options::pin_notice(ok).to_owned().into(),
                    Err(words) => format!("Can't add to the Home screen: {words}.").into(),
                });
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// The "Calls" section under the numbers.
    pub(crate) fn call_options_section(
        &self,
        id: i64,
        name: &str,
        has_number: bool,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !has_number {
            return None;
        }
        let colors = theme::colors(theme);
        let options = self.card_options.ready(id);
        let ringtone = if self.card_options.loading(id) {
            "…".to_owned()
        } else {
            options.map_or_else(
                || contact_options::ringtone_text(&ContactOptions::default()),
                contact_options::ringtone_text,
            )
        };
        let voicemail = options.is_some_and(|o| o.to_voicemail);
        let name = name.to_owned();
        let value = |text: String, on: bool| {
            div()
                .text_size(typography::FOOTNOTE.size)
                .text_color(if on {
                    colors.accent
                } else {
                    colors.secondary_label
                })
                .child(SharedString::from(text))
        };
        Some(
            section(
                theme,
                Some("Calls".into()),
                [
                    tap_row(
                        theme,
                        "card-ringtone",
                        Some(Icon::LucideBell),
                        "Ringtone",
                        Tone::Plain,
                    )
                    .justify_between()
                    .child(value(ringtone, false))
                    .on_click(cx.listener(move |this, _, _, cx| this.pick_ringtone(id, cx)))
                    .into_any_element(),
                    tap_row(
                        theme,
                        "card-voicemail",
                        Some(Icon::LucidePhone),
                        "Send calls to voicemail",
                        Tone::Plain,
                    )
                    .justify_between()
                    .child(value(
                        if voicemail { "On" } else { "Off" }.to_owned(),
                        voicemail,
                    ))
                    .on_click(
                        cx.listener(move |this, _, _, cx| this.toggle_voicemail(id, &name, cx)),
                    )
                    .into_any_element(),
                ],
            )
            .into_any_element(),
        )
    }

    /// The confirmation before calls go to voicemail.
    pub(crate) fn voicemail_card_text(name: &str) -> (String, String) {
        contact_options::voicemail_confirm(name)
    }

    pub(crate) fn set_summary_open(&mut self, open: bool, cx: &mut Context<Self>) {
        self.summary_open = open;
        self.clear_notice();
        self.page.restart();
        if open {
            self.summary = SummaryState::Loading;
            cx.spawn(async move |this, cx| {
                let result = cx
                    .background_executor()
                    .spawn(async move { backend::summary() })
                    .await;
                this.update(cx, |this, cx| {
                    if this.summary_open {
                        this.summary = match result {
                            Ok(s) => SummaryState::Ready(s),
                            Err(words) => SummaryState::Failed(words),
                        };
                    }
                    cx.notify();
                })
                .ok();
            })
            .detach();
        }
        cx.notify();
    }

    /// The Settings row that opens the summary.
    pub(crate) fn summary_entry(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let n = self.index().map_or(0, |i| i.all().count());
        section(
            theme,
            Some("Contacts".into()),
            [
                list_row(theme, None, contact_stats::total_text(n), None, None)
                    .id("contacts-summary")
                    .on_click(cx.listener(|this, _, _, cx| this.set_summary_open(true, cx)))
                    .into_any_element(),
            ],
        )
        .into_any_element()
    }

    /// The read-only summary page.
    pub(crate) fn summary_page(
        &self,
        window: &gpui::Window,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let top = div().px(px(8.0)).pt(px(48.0)).child(
            glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                .id("summary-back")
                .on_click(cx.listener(|this, _, _, cx| this.set_summary_open(false, cx))),
        );
        let lines = |rows: Vec<(String, String)>, tag: &'static str| -> Vec<AnyElement> {
            rows.into_iter()
                .enumerate()
                .map(|(n, (label, value))| {
                    list_row(theme, None, label, None, Some(value.into()))
                        .id((tag, n))
                        .into_any_element()
                })
                .collect()
        };
        let body = match &self.summary {
            SummaryState::Loading => section(
                theme,
                None,
                [list_row(theme, None, "Counting contacts…", None, None).into_any_element()],
            )
            .into_any_element(),
            SummaryState::Failed(words) => empty_state(
                theme,
                "Can't Count",
                format!("Can't read the address book: {words}."),
            )
            .into_any_element(),
            SummaryState::Ready(s) => {
                let (overview, accounts) = summary_sections(s);
                div()
                    .flex()
                    .flex_col()
                    .gap(px(20.0))
                    .child(section(theme, None, lines(overview, "summary-row")))
                    .children((!accounts.is_empty()).then(|| {
                        section(
                            theme,
                            Some("Accounts".into()),
                            lines(accounts, "summary-account"),
                        )
                    }))
                    .into_any_element()
            }
        };
        div()
            .child(top)
            .child(large_title(window, theme, "Contacts", None))
            .child(body)
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_turning_voicemail_on_asks() {
        assert!(matches!(
            voicemail_toggle(false, true, 7, "Ayse"),
            Some(Confirm::Voicemail { id: 7, .. })
        ));
        assert!(voicemail_toggle(true, false, 7, "Ayse").is_none());
        assert!(voicemail_toggle(true, true, 7, "Ayse").is_none());
        assert!(voicemail_toggle(false, false, 7, "Ayse").is_none());
    }

    #[test]
    fn picked_json_reads_default_silent_custom_and_cancel() {
        assert_eq!(
            parse_picked(r#"{"uri":null,"cancelled":false}"#),
            Some(Picked::Chosen(None))
        );
        assert_eq!(
            parse_picked(r#"{"uri":"","cancelled":false}"#),
            Some(Picked::Chosen(Some(String::new())))
        );
        assert_eq!(
            parse_picked(r#"{"uri":"content://media/1","cancelled":false}"#),
            Some(Picked::Chosen(Some("content://media/1".to_owned())))
        );
        assert_eq!(
            parse_picked(r#"{"uri":null,"cancelled":true}"#),
            Some(Picked::Cancelled)
        );
        assert_eq!(parse_picked("nope"), None);
    }

    #[test]
    fn summary_rows_split_overview_from_accounts() {
        let s = Summary {
            total: 3,
            with_number: 2,
            with_email: 1,
            with_birthday: 1,
            starred: 1,
            accounts: vec![("a@b.c".to_owned(), 2), ("".to_owned(), 1)],
        };
        let (overview, accounts) = summary_sections(&s);
        let labels: Vec<_> = overview.iter().map(|(l, _)| l.as_str()).collect();
        assert_eq!(
            labels,
            [
                "Total",
                "With a number",
                "With an email",
                "With a birthday",
                "Favorites"
            ]
        );
        assert_eq!(overview[0].1, "3 contacts");
        assert_eq!(accounts.len(), 2);
        assert_eq!(accounts[0].1, "2");
        let (overview, accounts) = summary_sections(&Summary::default());
        assert_eq!(overview.len(), 5);
        assert!(accounts.is_empty());
    }
}
