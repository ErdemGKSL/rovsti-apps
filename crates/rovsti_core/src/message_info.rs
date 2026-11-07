//! The rows of a message's Info sheet and the text Copy puts on the clipboard.

use crate::call_notes::when_line;
use crate::recents::LocaleTime;
use crate::sms::segments;

/// Delivery state of a message.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InfoState {
    Pending,
    Sent,
    Delivered,
    Read,
    Played,
    Failed,
    Received,
}

impl InfoState {
    pub fn label(self) -> &'static str {
        match self {
            Self::Pending => "Sending",
            Self::Sent => "Sent",
            Self::Delivered => "Delivered",
            Self::Read => "Read",
            Self::Played => "Played",
            Self::Failed => "Not sent",
            Self::Received => "Received",
        }
    }
}

/// How the message travelled.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InfoChannel {
    WhatsApp,
    Sms,
    Mms,
}

/// Everything known about one message; unknown fields stay at their default.
#[derive(Clone, Debug, Default)]
pub struct InfoInput<'a> {
    pub channel: Option<InfoChannel>,
    pub at_ms: i64,
    pub outgoing: bool,
    pub state: Option<InfoState>,
    pub author: Option<&'a str>,
    pub edited_ms: Option<i64>,
    pub forwarded: bool,
    pub starred: bool,
    pub sim: Option<&'a str>,
    pub text: Option<&'a str>,
    /// Short description from the caller, e.g. "Photo, 1280 × 720".
    pub media: Option<&'a str>,
}

/// One label and value line.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct InfoRow {
    pub label: &'static str,
    pub value: String,
}

fn row(label: &'static str, value: impl Into<String>) -> InfoRow {
    InfoRow {
        label,
        value: value.into(),
    }
}

fn known(s: Option<&str>) -> Option<&str> {
    s.map(str::trim).filter(|s| !s.is_empty())
}

/// Info rows in display order, leaving out what is unknown.
pub fn rows(input: &InfoInput<'_>, locale: LocaleTime) -> Vec<InfoRow> {
    let mut out = Vec::new();
    if !input.outgoing
        && let Some(author) = known(input.author)
    {
        out.push(row("From", author));
    }
    if input.at_ms > 0 {
        let label = if input.outgoing { "Sent" } else { "Received" };
        out.push(row(label, when_line(input.at_ms, locale)));
    }
    if input.outgoing
        && let Some(state) = input.state
    {
        out.push(row("Status", state.label()));
    }
    if let Some(ms) = input.edited_ms.filter(|ms| *ms > 0) {
        out.push(row("Edited", when_line(ms, locale)));
    }
    if input.forwarded {
        out.push(row("Forwarded", "Yes"));
    }
    if input.starred {
        out.push(row("Starred", "Yes"));
    }
    if let Some(channel) = input.channel {
        out.push(row(
            "Via",
            match channel {
                InfoChannel::WhatsApp => "WhatsApp",
                InfoChannel::Sms => "SMS",
                InfoChannel::Mms => "MMS",
            },
        ));
    }
    if let Some(sim) = known(input.sim) {
        out.push(row("SIM", sim));
    }
    if let Some(text) = input.text.filter(|t| !t.is_empty()) {
        let chars = text.chars().count();
        let unit = if chars == 1 {
            "character"
        } else {
            "characters"
        };
        let value = if input.channel == Some(InfoChannel::Sms) {
            let parts = segments(text).parts;
            format!("{chars} {unit}, {parts} SMS")
        } else {
            format!("{chars} {unit}")
        };
        out.push(row("Length", value));
    }
    if let Some(media) = known(input.media) {
        out.push(row("Media", media));
    }
    out
}

/// "Label: value" lines joined by newlines, for Copy.
pub fn info_text(rows: &[InfoRow]) -> String {
    rows.iter()
        .map(|r| format!("{}: {}", r.label, r.value))
        .collect::<Vec<_>>()
        .join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;

    const AT: i64 = 1_762_353_120_000;

    fn loc() -> LocaleTime {
        LocaleTime::default()
    }

    fn labels(rows: &[InfoRow]) -> Vec<&'static str> {
        rows.iter().map(|r| r.label).collect()
    }

    #[test]
    fn incoming_wa_rows() {
        let input = InfoInput {
            channel: Some(InfoChannel::WhatsApp),
            at_ms: AT,
            author: Some("Ada"),
            text: Some("hello"),
            state: Some(InfoState::Received),
            ..InfoInput::default()
        };
        let r = rows(&input, loc());
        assert_eq!(labels(&r), ["From", "Received", "Via", "Length"]);
        assert_eq!(r[0].value, "Ada");
        assert_eq!(r[1].value, when_line(AT, loc()));
        assert_eq!(r[2].value, "WhatsApp");
        assert_eq!(r[3].value, "5 characters");
    }

    #[test]
    fn outgoing_sms_shows_status_sim_and_segments() {
        let text = "a".repeat(152);
        let input = InfoInput {
            channel: Some(InfoChannel::Sms),
            at_ms: AT,
            outgoing: true,
            state: Some(InfoState::Delivered),
            sim: Some("SIM 2"),
            author: Some("Me"),
            text: Some(&text),
            ..InfoInput::default()
        };
        let r = rows(&input, loc());
        assert_eq!(labels(&r), ["Sent", "Status", "Via", "SIM", "Length"]);
        assert_eq!(r[1].value, "Delivered");
        assert_eq!(r[3].value, "SIM 2");
        assert_eq!(r[4].value, "152 characters, 1 SMS");
        let long = "a".repeat(200);
        let input = InfoInput {
            text: Some(&long),
            ..input
        };
        assert_eq!(
            rows(&input, loc()).last().map(|r| r.value.as_str()),
            Some("200 characters, 2 SMS")
        );
    }

    #[test]
    fn edited_and_forwarded_flags() {
        let input = InfoInput {
            at_ms: AT,
            edited_ms: Some(AT + 60_000),
            forwarded: true,
            starred: true,
            media: Some("Photo, 1280 × 720"),
            ..InfoInput::default()
        };
        let r = rows(&input, loc());
        assert_eq!(
            labels(&r),
            ["Received", "Edited", "Forwarded", "Starred", "Media"]
        );
        assert_eq!(r[1].value, when_line(AT + 60_000, loc()));
        assert_eq!(r[2].value, "Yes");
        assert_eq!(r[4].value, "Photo, 1280 × 720");
    }

    #[test]
    fn unknown_fields_are_left_out() {
        assert!(rows(&InfoInput::default(), loc()).is_empty());
        let input = InfoInput {
            author: Some("  "),
            sim: Some(""),
            text: Some(""),
            media: Some(" "),
            edited_ms: Some(0),
            ..InfoInput::default()
        };
        assert!(rows(&input, loc()).is_empty());
        // Status only matters for outgoing messages.
        let input = InfoInput {
            state: Some(InfoState::Read),
            ..InfoInput::default()
        };
        assert!(rows(&input, loc()).is_empty());
    }

    #[test]
    fn info_text_joins_lines() {
        let r = vec![row("Sent", "Thu 5 Nov 2026, 14:32"), row("Status", "Read")];
        assert_eq!(info_text(&r), "Sent: Thu 5 Nov 2026, 14:32\nStatus: Read");
        assert_eq!(info_text(&[]), "");
    }
}
