//! Polls: what a new poll must be, how votes add up, and what a poll bubble shows.
//!
//! WhatsApp keeps one vote per person: a later vote replaces the earlier one and an empty vote takes it back
//! (whatsapp-rust 0.7.0 `aggregate_votes`, last vote wins). A voter may arrive under their phone number or their LID;
//! both name the same vote when the pair is known.

use serde::{Deserialize, Serialize};

use crate::wa_identity;

/// Fewest options a poll has (whatsapp-rust 0.7.0 `build_poll_creation_message`).
pub const MIN_OPTIONS: usize = 2;
/// Most options a poll has.
pub const MAX_OPTIONS: usize = 12;
/// Longest question, in characters (WhatsApp's field limit as remembered, not verified).
pub const MAX_QUESTION_CHARS: usize = 255;
/// Longest option, in characters (as above).
pub const MAX_OPTION_CHARS: usize = 100;

/// A poll as the composer's sheet fills it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct PollDraft {
    pub question: String,
    pub options: Vec<String>,
    /// "Allow multiple answers".
    #[serde(default)]
    pub multiple: bool,
}

/// Why a draft cannot be sent yet. Option indexes are those of the cleaned draft.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum PollProblem {
    NoQuestion,
    QuestionTooLong,
    TooFewOptions,
    TooManyOptions,
    OptionTooLong {
        index: usize,
    },
    /// The same text as an earlier option: its votes could not be told apart.
    Duplicate {
        index: usize,
    },
}

/// The draft as it is sent (trimmed, empty options dropped), or what is wrong with it.
pub fn check_draft(draft: &PollDraft) -> Result<PollDraft, Vec<PollProblem>> {
    let question = draft.question.trim().to_owned();
    let options: Vec<String> = draft
        .options
        .iter()
        .map(|o| o.trim().to_owned())
        .filter(|o| !o.is_empty())
        .collect();
    let mut problems = Vec::new();
    if question.is_empty() {
        problems.push(PollProblem::NoQuestion);
    } else if question.chars().count() > MAX_QUESTION_CHARS {
        problems.push(PollProblem::QuestionTooLong);
    }
    if options.len() < MIN_OPTIONS {
        problems.push(PollProblem::TooFewOptions);
    } else if options.len() > MAX_OPTIONS {
        problems.push(PollProblem::TooManyOptions);
    }
    for (index, option) in options.iter().enumerate() {
        if option.chars().count() > MAX_OPTION_CHARS {
            problems.push(PollProblem::OptionTooLong { index });
        }
        if options[..index].contains(option) {
            problems.push(PollProblem::Duplicate { index });
        }
    }
    if problems.is_empty() {
        Ok(PollDraft {
            question,
            options,
            multiple: draft.multiple,
        })
    } else {
        Err(problems)
    }
}

/// `selectableOptionsCount` on the wire: 1 for one answer, every option for "multiple answers".
pub fn selectable_count(multiple: bool, options: usize) -> u32 {
    if multiple {
        u32::try_from(options.max(1)).unwrap_or(u32::MAX)
    } else {
        1
    }
}

/// Why a choice cannot be sent as a vote.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum ChoiceProblem {
    /// A name that is not one of the poll's options.
    NotAnOption { name: String },
    /// More answers than the poll allows.
    TooMany { allowed: u32 },
}

/// The vote to send for `choice` (duplicates dropped, in the poll's option order), or why it cannot go. An empty
/// choice is allowed: it takes the vote back.
pub fn check_choice(
    options: &[String],
    selectable: u32,
    choice: &[String],
) -> Result<Vec<String>, ChoiceProblem> {
    if let Some(name) = choice.iter().find(|name| !options.contains(name)) {
        return Err(ChoiceProblem::NotAnOption { name: name.clone() });
    }
    let picked: Vec<String> = options
        .iter()
        .filter(|option| choice.contains(option))
        .cloned()
        .collect();
    // 0 on the wire means no limit (older senders).
    let allowed = if selectable == 0 {
        u32::MAX
    } else {
        selectable
    };
    if picked.len() as u64 > u64::from(allowed) {
        return Err(ChoiceProblem::TooMany { allowed });
    }
    Ok(picked)
}

/// One person's vote: the option names they picked (empty: taken back), when they voted.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Vote {
    pub voter: String,
    /// The voter's other id (LID or number), when it is known.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub voter_alt: Option<String>,
    pub options: Vec<String>,
    pub at_ms: i64,
}

/// The bare ids of a voter (device suffixes dropped).
fn ids_of(vote: &Vote) -> Vec<String> {
    std::iter::once(vote.voter.as_str())
        .chain(vote.voter_alt.as_deref())
        .map(|id| {
            wa_identity::parse(id)
                .map(|parsed| parsed.id.bare())
                .unwrap_or_else(|| id.trim().to_owned())
        })
        .collect()
}

/// Whether two votes come from the same person: an id of one is an id of the other.
pub fn same_voter(a: &Vote, b: &Vote) -> bool {
    let theirs = ids_of(b);
    ids_of(a).iter().any(|id| theirs.contains(id))
}

/// Each person's newest vote, in the order the people first voted. A vote at the same time as a stored one replaces
/// it (it came later). A newest vote that is empty leaves the person out.
pub fn latest_votes(votes: &[Vote]) -> Vec<Vote> {
    let mut latest: Vec<Vote> = Vec::new();
    for vote in votes {
        match latest.iter_mut().find(|kept| same_voter(kept, vote)) {
            Some(kept) if vote.at_ms >= kept.at_ms => {
                let alt = vote.voter_alt.clone().or_else(|| kept.voter_alt.take());
                *kept = Vote {
                    voter_alt: alt,
                    ..vote.clone()
                };
            }
            Some(_) => {}
            None => latest.push(vote.clone()),
        }
    }
    latest.retain(|vote| !vote.options.is_empty());
    latest
}

/// The voters of one option.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct OptionTally {
    pub name: String,
    pub voters: Vec<String>,
}

/// The voters of every option of `options`, in option order, from everyone's newest vote. A name a vote picked
/// that is not an option is ignored; a voter counts once per option.
pub fn tally(options: &[String], votes: &[Vote]) -> Vec<OptionTally> {
    let latest = latest_votes(votes);
    options
        .iter()
        .map(|name| OptionTally {
            name: name.clone(),
            voters: latest
                .iter()
                .filter(|vote| vote.options.contains(name))
                .map(|vote| vote.voter.clone())
                .collect(),
        })
        .collect()
}

/// One option as the bubble draws it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct OptionView {
    pub name: String,
    pub count: u32,
    /// The bar's length: this option's voters out of everyone who voted, 0 to 100.
    pub percent: u32,
    /// I picked it.
    pub mine: bool,
    /// It has the most votes (ties all lead; nothing leads with no votes).
    pub leading: bool,
    pub voters: Vec<String>,
}

/// What a poll bubble shows.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PollView {
    pub question: String,
    pub multiple: bool,
    /// People who voted (one each, whatever they picked).
    pub voters: u32,
    pub options: Vec<OptionView>,
    /// I have voted.
    pub voted: bool,
}

/// The bubble of a poll whose tally is `options` and my choice `mine`. `own` are my ids: a voter that is one of them
/// is me, whatever the gateway's `mine` says (my vote from another device).
pub fn poll_view(
    question: &str,
    selectable: u32,
    options: &[OptionTally],
    mine: &[String],
    own: &[String],
) -> PollView {
    let me = Vote {
        voter: own.first().cloned().unwrap_or_default(),
        voter_alt: own.get(1).cloned(),
        options: Vec::new(),
        at_ms: 0,
    };
    let is_me = |voter: &String| {
        !own.is_empty()
            && same_voter(
                &me,
                &Vote {
                    voter: voter.clone(),
                    voter_alt: None,
                    options: Vec::new(),
                    at_ms: 0,
                },
            )
    };
    let mut people: Vec<&String> = Vec::new();
    for voter in options.iter().flat_map(|o| &o.voters) {
        if !people.contains(&voter) {
            people.push(voter);
        }
    }
    let total = people.len() as u32;
    let top = options.iter().map(|o| o.voters.len()).max().unwrap_or(0);
    let views = options
        .iter()
        .map(|option| {
            let count = option.voters.len() as u32;
            OptionView {
                name: option.name.clone(),
                count,
                percent: if total == 0 {
                    0
                } else {
                    (count * 100 + total / 2) / total
                },
                mine: mine.contains(&option.name) || option.voters.iter().any(is_me),
                leading: top > 0 && option.voters.len() == top,
                voters: option.voters.clone(),
            }
        })
        .collect::<Vec<_>>();
    let voted = views.iter().any(|o| o.mine);
    PollView {
        question: question.to_owned(),
        multiple: selectable != 1,
        voters: total,
        options: views,
        voted,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ALI: &str = "100000000001@s.whatsapp.net";
    const ALI_LID: &str = "777000000000001@lid";
    const BEN: &str = "100000000002@s.whatsapp.net";
    const ME: &str = "100000000009@s.whatsapp.net";

    fn names(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| (*s).to_owned()).collect()
    }

    fn vote(voter: &str, alt: Option<&str>, options: &[&str], at_ms: i64) -> Vote {
        Vote {
            voter: voter.to_owned(),
            voter_alt: alt.map(str::to_owned),
            options: names(options),
            at_ms,
        }
    }

    #[test]
    fn a_draft_is_trimmed_and_needs_a_question_and_two_options() {
        let draft = PollDraft {
            question: "  Lunch?  ".into(),
            options: names(&[" Pizza ", "", "Soup", "  "]),
            multiple: true,
        };
        let clean = check_draft(&draft).unwrap();
        assert_eq!(clean.question, "Lunch?");
        assert_eq!(clean.options, names(&["Pizza", "Soup"]));
        assert!(clean.multiple);

        let bad = PollDraft {
            question: " ".into(),
            options: names(&["Only"]),
            multiple: false,
        };
        assert_eq!(
            check_draft(&bad).unwrap_err(),
            vec![PollProblem::NoQuestion, PollProblem::TooFewOptions]
        );
    }

    #[test]
    fn a_draft_refuses_thirteen_options_duplicates_and_long_texts() {
        let many: Vec<String> = (0..13).map(|i| format!("o{i}")).collect();
        let draft = PollDraft {
            question: "q".into(),
            options: many,
            multiple: false,
        };
        assert_eq!(
            check_draft(&draft).unwrap_err(),
            vec![PollProblem::TooManyOptions]
        );
        let twelve: Vec<String> = (0..12).map(|i| format!("o{i}")).collect();
        assert!(
            check_draft(&PollDraft {
                question: "q".into(),
                options: twelve,
                multiple: false
            })
            .is_ok()
        );
        let draft = PollDraft {
            question: "q".repeat(MAX_QUESTION_CHARS + 1),
            options: vec!["a".into(), "a".into(), "é".repeat(MAX_OPTION_CHARS + 1)],
            multiple: false,
        };
        assert_eq!(
            check_draft(&draft).unwrap_err(),
            vec![
                PollProblem::QuestionTooLong,
                PollProblem::Duplicate { index: 1 },
                PollProblem::OptionTooLong { index: 2 },
            ]
        );
    }

    #[test]
    fn selectable_count_is_one_or_every_option() {
        assert_eq!(selectable_count(false, 4), 1);
        assert_eq!(selectable_count(true, 4), 4);
    }

    #[test]
    fn a_choice_must_name_options_and_respect_the_limit() {
        let options = names(&["A", "B", "C"]);
        assert_eq!(
            check_choice(&options, 3, &names(&["C", "A", "A"])).unwrap(),
            names(&["A", "C"])
        );
        assert_eq!(
            check_choice(&options, 1, &[]).unwrap(),
            Vec::<String>::new()
        );
        assert_eq!(
            check_choice(&options, 1, &names(&["A", "B"])),
            Err(ChoiceProblem::TooMany { allowed: 1 })
        );
        assert_eq!(
            check_choice(&options, 1, &names(&["Z"])),
            Err(ChoiceProblem::NotAnOption { name: "Z".into() })
        );
        // An older sender's 0 is no limit.
        assert!(check_choice(&options, 0, &options).is_ok());
    }

    #[test]
    fn the_newest_vote_wins_and_an_empty_one_takes_it_back() {
        let options = names(&["A", "B"]);
        let votes = vec![
            vote(ALI, None, &["A"], 10),
            vote(BEN, None, &["A"], 11),
            // Ali changes his vote.
            vote(ALI, None, &["B"], 20),
            // An older vote of Ben's that arrived late changes nothing.
            vote(BEN, None, &["B"], 5),
        ];
        let result = tally(&options, &votes);
        assert_eq!(result[0].voters, names(&[BEN]));
        assert_eq!(result[1].voters, names(&[ALI]));

        let mut taken_back = votes.clone();
        taken_back.push(vote(BEN, None, &[], 30));
        let result = tally(&options, &taken_back);
        assert!(result[0].voters.is_empty());
        assert_eq!(result[1].voters, names(&[ALI]));
    }

    #[test]
    fn a_voter_under_both_ids_counts_once() {
        let options = names(&["A", "B"]);
        // First by LID only (a LID-only member), then by number with the pair known.
        let votes = vec![
            vote(ALI_LID, None, &["A"], 10),
            vote(ALI, Some(ALI_LID), &["B"], 20),
        ];
        let latest = latest_votes(&votes);
        assert_eq!(latest.len(), 1);
        assert_eq!(latest[0].voter, ALI);
        let result = tally(&options, &votes);
        assert!(result[0].voters.is_empty());
        assert_eq!(result[1].voters, names(&[ALI]));
        // A device suffix is the same person.
        let devices = vec![
            vote("100000000002:3@s.whatsapp.net", None, &["A"], 1),
            vote(BEN, None, &["B"], 2),
        ];
        assert_eq!(latest_votes(&devices).len(), 1);
    }

    #[test]
    fn names_that_are_not_options_are_ignored() {
        let result = tally(&names(&["A"]), &[vote(ALI, None, &["A", "Gone"], 1)]);
        assert_eq!(
            result,
            vec![OptionTally {
                name: "A".into(),
                voters: names(&[ALI])
            }]
        );
    }

    #[test]
    fn the_view_counts_people_and_marks_mine_and_the_leader() {
        let options = vec![
            OptionTally {
                name: "A".into(),
                voters: names(&[ALI, ME]),
            },
            OptionTally {
                name: "B".into(),
                voters: names(&[ALI]),
            },
            OptionTally {
                name: "C".into(),
                voters: vec![],
            },
        ];
        let view = poll_view("Lunch?", 3, &options, &[], &names(&[ME]));
        assert!(view.multiple);
        assert_eq!(view.voters, 2);
        assert!(view.voted);
        let a = &view.options[0];
        assert_eq!(
            (a.count, a.percent, a.mine, a.leading),
            (2, 100, true, true)
        );
        let b = &view.options[1];
        assert_eq!(
            (b.count, b.percent, b.mine, b.leading),
            (1, 50, false, false)
        );
        let c = &view.options[2];
        assert_eq!((c.count, c.percent, c.leading), (0, 0, false));

        // No votes: nothing leads; the gateway's `my_votes` marks a choice not counted yet.
        let empty = vec![OptionTally {
            name: "A".into(),
            voters: vec![],
        }];
        let view = poll_view("Q", 1, &empty, &names(&["A"]), &[]);
        assert!(!view.multiple);
        assert_eq!(view.voters, 0);
        assert!(view.options[0].mine && !view.options[0].leading);
    }
}
