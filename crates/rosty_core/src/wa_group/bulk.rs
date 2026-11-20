//! The per-person results of a bulk action (add, remove, promote, demote, approve, reject, create).
//!
//! WhatsApp answers one result per person: no error, or an error code. Only `403` with an invite code is named in
//! the library (`wacore::iq::groups::ParticipantChangeResponse`: the person's privacy refuses adds, and the code lets
//! the adder send them an invite instead). The rest of the table is WhatsApp Web's as remembered, not verified: `404`
//! not on WhatsApp, `408` left recently, `409` already in the group, `500` the group is full, `401` and `406` not
//! allowed. An unknown code is "failed" with the code kept.

use serde::{Deserialize, Serialize};

/// Which action the results are of.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BulkKind {
    Add,
    Remove,
    Promote,
    Demote,
    Approve,
    Reject,
    Create,
}

impl BulkKind {
    fn adds(self) -> bool {
        matches!(self, Self::Add | Self::Create | Self::Approve)
    }
}

/// What happened to one person.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Outcome {
    Done,
    /// Their privacy refuses adds; an invite can be sent instead.
    InviteInstead,
    /// Their privacy refuses adds and no invite was offered.
    Privacy,
    NotOnWhatsApp,
    Already,
    Full,
    LeftRecently,
    NotAllowed,
    Failed,
}

/// The outcome of `error` (none: done) for `kind`; `invite` is whether the server gave an invite code.
#[must_use]
pub fn outcome(kind: BulkKind, error: Option<&str>, invite: bool) -> Outcome {
    let Some(code) = error
        .map(str::trim)
        .filter(|c| !c.is_empty() && *c != "200")
    else {
        return Outcome::Done;
    };
    match code {
        "403" if kind.adds() && invite => Outcome::InviteInstead,
        "403" if kind.adds() => Outcome::Privacy,
        "403" | "401" | "406" => Outcome::NotAllowed,
        "404" if kind.adds() => Outcome::NotOnWhatsApp,
        "408" if kind.adds() => Outcome::LeftRecently,
        "409" if kind.adds() => Outcome::Already,
        "500" if kind.adds() => Outcome::Full,
        _ => Outcome::Failed,
    }
}

/// One result as the gateway answers it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct BulkResult {
    pub jid: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    /// The server gave an invite code for them.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub invite: bool,
}

/// One person's line in the results sheet.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ResultView {
    pub jid: String,
    pub outcome: Outcome,
    /// The server's code of a failure the table does not name.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
}

/// What the sheet shows after a bulk action.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct BulkSummary {
    pub done: usize,
    /// Everyone it did not work for, in request order (the people who can get an invite first).
    pub problems: Vec<ResultView>,
    /// The people an invite can be sent to.
    pub invites: Vec<String>,
}

impl BulkSummary {
    /// Everything worked.
    #[must_use]
    pub fn all_done(&self) -> bool {
        self.problems.is_empty()
    }
}

/// The summary of `results` of `kind`. A person named twice counts once (their first result).
#[must_use]
pub fn summarize(kind: BulkKind, results: &[BulkResult]) -> BulkSummary {
    let mut seen: Vec<&str> = Vec::new();
    let mut done = 0;
    let mut problems = Vec::new();
    for result in results {
        if seen.iter().any(|s| super::members::same_id(s, &result.jid)) {
            continue;
        }
        seen.push(&result.jid);
        match outcome(kind, result.error.as_deref(), result.invite) {
            Outcome::Done => done += 1,
            outcome => problems.push(ResultView {
                jid: result.jid.clone(),
                outcome,
                code: (outcome == Outcome::Failed)
                    .then(|| result.error.clone())
                    .flatten(),
            }),
        }
    }
    problems.sort_by_key(|p| p.outcome != Outcome::InviteInstead);
    let invites = problems
        .iter()
        .filter(|p| p.outcome == Outcome::InviteInstead)
        .map(|p| p.jid.clone())
        .collect();
    BulkSummary {
        done,
        problems,
        invites,
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::*;

    fn result(jid: &str, error: Option<&str>, invite: bool) -> BulkResult {
        BulkResult {
            jid: jid.into(),
            error: error.map(str::to_owned),
            invite,
        }
    }

    #[test]
    fn codes_map_to_outcomes_per_action() {
        use BulkKind::{Add, Create, Demote, Remove};
        assert_eq!(outcome(Add, None, false), Outcome::Done);
        assert_eq!(outcome(Add, Some("200"), false), Outcome::Done);
        assert_eq!(outcome(Add, Some("403"), true), Outcome::InviteInstead);
        assert_eq!(outcome(Create, Some("403"), false), Outcome::Privacy);
        assert_eq!(outcome(Remove, Some("403"), false), Outcome::NotAllowed);
        assert_eq!(outcome(Add, Some("404"), false), Outcome::NotOnWhatsApp);
        assert_eq!(outcome(Add, Some("408"), false), Outcome::LeftRecently);
        assert_eq!(outcome(Add, Some("409"), false), Outcome::Already);
        assert_eq!(outcome(Add, Some("500"), false), Outcome::Full);
        assert_eq!(outcome(Demote, Some("406"), false), Outcome::NotAllowed);
        assert_eq!(outcome(Remove, Some("404"), false), Outcome::Failed);
        assert_eq!(outcome(Add, Some("418"), false), Outcome::Failed);
    }

    #[test]
    fn a_summary_counts_and_lists_invites_first() {
        let summary = summarize(
            BulkKind::Add,
            &[
                result("100000000001@s.whatsapp.net", None, false),
                result("100000000002@s.whatsapp.net", Some("409"), false),
                result("100000000003@s.whatsapp.net", Some("403"), true),
                result("100000000004@s.whatsapp.net", Some("500"), false),
                result("100000000005@s.whatsapp.net", Some("418"), false),
                result("100000000001:2@s.whatsapp.net", Some("409"), false),
            ],
        );
        assert_eq!(summary.done, 1);
        assert!(!summary.all_done());
        let outcomes: Vec<Outcome> = summary.problems.iter().map(|p| p.outcome).collect();
        assert_eq!(
            outcomes,
            vec![
                Outcome::InviteInstead,
                Outcome::Already,
                Outcome::Full,
                Outcome::Failed
            ]
        );
        assert_eq!(summary.problems[3].code.as_deref(), Some("418"));
        assert_eq!(summary.problems[1].code, None);
        assert_eq!(
            summary.invites,
            vec!["100000000003@s.whatsapp.net".to_owned()]
        );
        assert!(summarize(BulkKind::Remove, &[result("1@s.whatsapp.net", None, false)]).all_done());
    }
}
