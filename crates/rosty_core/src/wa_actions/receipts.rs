//! Message info: who got, read or played one of my messages, as the sheet lists it.

/// One member's receipts of a message, as the gateway reports them.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct MemberReceipt {
    pub jid: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub delivered_ms: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub read_ms: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub played_ms: Option<i64>,
    /// No longer in the group.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub left: bool,
}

/// A section of the sheet, in the order shown.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReceiptSection {
    /// A voice or video note was played.
    Played,
    Read,
    Delivered,
    /// Sent, nothing back yet.
    Waiting,
}

/// One row of a section: who, and when they got that far (`None` in [`ReceiptSection::Waiting`], or when the
/// gateway lost the time).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ReceiptRow {
    pub jid: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub at_ms: Option<i64>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub left: bool,
}

/// A non-empty section and its rows.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct SectionRows {
    pub section: ReceiptSection,
    pub rows: Vec<ReceiptRow>,
}

/// Sorts the members into sections: a member who played a note is in Played (only for a `voice` note; otherwise a
/// play counts as read), else Read, else Delivered, else Waiting. In each section the latest time comes first,
/// members who left the group after everyone else, then by id; empty sections are left out.
#[must_use]
pub fn sections(members: &[MemberReceipt], voice: bool) -> Vec<SectionRows> {
    let mut out: Vec<SectionRows> = Vec::new();
    for member in members {
        let (section, at_ms) = if voice && member.played_ms.is_some() {
            (ReceiptSection::Played, member.played_ms)
        } else if member.read_ms.is_some() || member.played_ms.is_some() {
            (ReceiptSection::Read, member.read_ms.or(member.played_ms))
        } else if member.delivered_ms.is_some() {
            (ReceiptSection::Delivered, member.delivered_ms)
        } else {
            (ReceiptSection::Waiting, None)
        };
        let row = ReceiptRow {
            jid: member.jid.clone(),
            at_ms,
            left: member.left,
        };
        match out.iter_mut().find(|s| s.section == section) {
            Some(rows) => rows.rows.push(row),
            None => out.push(SectionRows {
                section,
                rows: vec![row],
            }),
        }
    }
    out.sort_by_key(|s| s.section);
    for section in &mut out {
        section.rows.sort_by(|a, b| {
            a.left
                .cmp(&b.left)
                .then(b.at_ms.cmp(&a.at_ms))
                .then(a.jid.cmp(&b.jid))
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn member(jid: &str, d: Option<i64>, r: Option<i64>, p: Option<i64>, left: bool) -> MemberReceipt {
        MemberReceipt {
            jid: jid.to_owned(),
            delivered_ms: d,
            read_ms: r,
            played_ms: p,
            left,
        }
    }

    #[test]
    fn sorts_members_into_sections() {
        let members = [
            member("a", Some(1), Some(5), None, false),
            member("b", Some(2), None, None, false),
            member("c", None, None, None, false),
            member("d", Some(1), Some(7), Some(9), false),
            member("e", Some(1), Some(8), None, true),
            member("f", None, Some(6), None, false),
        ];
        let got = sections(&members, false);
        let names: Vec<(ReceiptSection, Vec<&str>)> = got
            .iter()
            .map(|s| (s.section, s.rows.iter().map(|r| r.jid.as_str()).collect()))
            .collect();
        assert_eq!(
            names,
            vec![
                (ReceiptSection::Read, vec!["d", "f", "a", "e"]),
                (ReceiptSection::Delivered, vec!["b"]),
                (ReceiptSection::Waiting, vec!["c"]),
            ]
        );
        // Read time, not the play, for a message that is not a voice note.
        assert_eq!(got[0].rows[0].at_ms, Some(7));
        assert!(got[0].rows[3].left);
    }

    #[test]
    fn voice_notes_have_a_played_section() {
        let members = [
            member("a", Some(1), Some(2), Some(3), false),
            member("b", Some(1), None, Some(4), false),
            member("c", Some(1), Some(2), None, false),
        ];
        let got = sections(&members, true);
        assert_eq!(got[0].section, ReceiptSection::Played);
        assert_eq!(got[0].rows.iter().map(|r| r.jid.as_str()).collect::<Vec<_>>(), ["b", "a"]);
        assert_eq!(got[1].section, ReceiptSection::Read);
        assert!(sections(&[], true).is_empty());
    }
}
