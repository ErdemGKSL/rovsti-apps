//! Rich content on the wire (E6-WA R5): polls, shared places and contact cards as message meta, and the requests
//! that send them and vote. All additive: a message keeps its `unsupported` body, so an older app shows an
//! unsupported message, and an older gateway answers the new routes with the router's 404.

use rosty_core::wa_rich::{self, ChoiceProblem, PollDraft, PollProblem};

use crate::meta::SharedContact;

fn is_false(b: &bool) -> bool {
    !*b
}

/// Most cards one contacts message carries.
pub const MAX_SHARED_CONTACTS: usize = 20;

/// One option of a poll and who picked it.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PollOption {
    pub name: String,
    /// Their ids as the gateway names people (the number first, else the LID).
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub voters: Vec<String>,
}

/// A poll ([`crate::MessageMeta::poll`]): its question, its options with their voters, and my choice.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Poll {
    pub question: String,
    pub options: Vec<PollOption>,
    /// How many options one person may pick: 1 is a single choice, more is "multiple answers" (0 from older
    /// senders: no limit).
    pub selectable: u32,
    /// The names I picked, as last voted from any of my devices.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub my_votes: Vec<String>,
}

impl Poll {
    pub fn option_names(&self) -> Vec<String> {
        self.options.iter().map(|o| o.name.clone()).collect()
    }

    /// "Allow multiple answers".
    pub fn multiple(&self) -> bool {
        self.selectable != 1
    }
}

/// What `POST /v1/messages/rich` sends.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum RichContent {
    Poll {
        question: String,
        options: Vec<String>,
        #[serde(default, skip_serializing_if = "is_false")]
        multiple: bool,
    },
    /// A place (a one-shot fix of mine, never a live location).
    Location {
        latitude: f64,
        longitude: f64,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        name: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        address: Option<String>,
    },
    /// One card goes as a contact message, several as one contacts array.
    Contacts { contacts: Vec<SharedContact> },
}

// Coordinates are never NaN once checked (`is_valid_coord`), so equality is total for every content that can be
// sent; the outbox rows that carry one compare as `Eq`.
impl Eq for RichContent {}

/// Why a [`RichContent`] cannot be sent.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum RichError {
    #[error("invalid poll: {0:?}")]
    Poll(Vec<PollProblem>),
    #[error("the coordinates name no place")]
    Coordinates,
    #[error("a contacts message needs 1 to {MAX_SHARED_CONTACTS} cards")]
    ContactCount,
    #[error("contact card {0} is empty or too large")]
    Card(usize),
}

impl RichContent {
    /// The content as it is sent (a poll trimmed), or why it cannot go.
    pub fn checked(&self) -> Result<RichContent, RichError> {
        match self {
            RichContent::Poll {
                question,
                options,
                multiple,
            } => {
                let draft = wa_rich::check_draft(&PollDraft {
                    question: question.clone(),
                    options: options.clone(),
                    multiple: *multiple,
                })
                .map_err(RichError::Poll)?;
                Ok(RichContent::Poll {
                    question: draft.question,
                    options: draft.options,
                    multiple: draft.multiple,
                })
            }
            RichContent::Location {
                latitude,
                longitude,
                name,
                address,
            } => {
                if !wa_rich::is_valid_coord(*latitude, *longitude) {
                    return Err(RichError::Coordinates);
                }
                let clean = |t: &Option<String>| {
                    t.as_deref()
                        .map(str::trim)
                        .filter(|t| !t.is_empty())
                        .map(str::to_owned)
                };
                Ok(RichContent::Location {
                    latitude: *latitude,
                    longitude: *longitude,
                    name: clean(name),
                    address: clean(address),
                })
            }
            RichContent::Contacts { contacts } => {
                if contacts.is_empty() || contacts.len() > MAX_SHARED_CONTACTS {
                    return Err(RichError::ContactCount);
                }
                if let Some(bad) = contacts.iter().position(|c| {
                    c.vcard.trim().is_empty() || c.vcard.len() > wa_rich::MAX_VCARD_BYTES
                }) {
                    return Err(RichError::Card(bad));
                }
                Ok(self.clone())
            }
        }
    }
}

impl RichContent {
    /// The meta the sent message will have, as the gateway describes it (a poll with no votes yet): what a phone
    /// shows while the send waits, and stores when the gateway's own description has not arrived.
    pub fn preview_meta(&self) -> crate::MessageMeta {
        match self {
            RichContent::Poll {
                question,
                options,
                multiple,
            } => crate::MessageMeta {
                poll: Some(Poll {
                    question: question.clone(),
                    options: options
                        .iter()
                        .map(|name| PollOption {
                            name: name.clone(),
                            voters: Vec::new(),
                        })
                        .collect(),
                    selectable: wa_rich::selectable_count(*multiple, options.len()),
                    my_votes: Vec::new(),
                }),
                ..Default::default()
            },
            RichContent::Location {
                latitude,
                longitude,
                name,
                address,
            } => crate::MessageMeta {
                location: Some(crate::Location {
                    latitude: *latitude,
                    longitude: *longitude,
                    name: name.clone(),
                    address: address.clone(),
                    ..Default::default()
                }),
                ..Default::default()
            },
            RichContent::Contacts { contacts } => crate::MessageMeta {
                contact: contacts.first().cloned(),
                contacts: if contacts.len() > 1 {
                    contacts.clone()
                } else {
                    Vec::new()
                },
                ..Default::default()
            },
        }
    }
}

/// Body of `POST /v1/messages/rich`.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct SendRich {
    pub chat: String,
    /// Reply to this message.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub quoted_id: Option<String>,
    pub content: RichContent,
}

/// Body of `POST /v1/polls/vote`: my choice in the poll `message_id` (empty: take my vote back).
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PollVote {
    pub chat: String,
    pub message_id: String,
    #[serde(default)]
    pub options: Vec<String>,
}

impl PollVote {
    /// The names to send for `poll` (in option order, duplicates dropped), or why the choice cannot go.
    pub fn checked(&self, poll: &Poll) -> Result<Vec<String>, ChoiceProblem> {
        wa_rich::check_choice(&poll.option_names(), poll.selectable, &self.options)
    }
}

/// Body of `POST /v1/presence/unsubscribe`.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct UnsubscribePresence {
    pub jid: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rich_content_reads_as_its_tag() {
        let poll: SendRich = serde_json::from_str(
            r#"{"chat":"g@g.us","content":{"type":"poll","question":"Q","options":["A","B"]}}"#,
        )
        .unwrap();
        assert_eq!(
            poll.content,
            RichContent::Poll {
                question: "Q".into(),
                options: vec!["A".into(), "B".into()],
                multiple: false
            }
        );
        let place = RichContent::Location {
            latitude: 41.0,
            longitude: 29.0,
            name: None,
            address: None,
        };
        assert_eq!(
            serde_json::to_string(&place).unwrap(),
            r#"{"type":"location","latitude":41.0,"longitude":29.0}"#
        );
    }

    #[test]
    fn content_is_checked_before_it_goes() {
        let poll = RichContent::Poll {
            question: " Q ".into(),
            options: vec![" A ".into(), "".into(), "B".into()],
            multiple: true,
        };
        assert_eq!(
            poll.checked().unwrap(),
            RichContent::Poll {
                question: "Q".into(),
                options: vec!["A".into(), "B".into()],
                multiple: true
            }
        );
        let one = RichContent::Poll {
            question: "Q".into(),
            options: vec!["A".into()],
            multiple: false,
        };
        assert_eq!(
            one.checked(),
            Err(RichError::Poll(vec![PollProblem::TooFewOptions]))
        );
        let nowhere = RichContent::Location {
            latitude: 91.0,
            longitude: 0.0,
            name: None,
            address: None,
        };
        assert_eq!(nowhere.checked(), Err(RichError::Coordinates));
        let none = RichContent::Contacts { contacts: vec![] };
        assert_eq!(none.checked(), Err(RichError::ContactCount));
        let blank = RichContent::Contacts {
            contacts: vec![SharedContact {
                display_name: "A".into(),
                vcard: " ".into(),
            }],
        };
        assert_eq!(blank.checked(), Err(RichError::Card(0)));
    }

    #[test]
    fn a_vote_is_checked_against_its_poll() {
        let poll = Poll {
            question: "Q".into(),
            options: vec![
                PollOption {
                    name: "A".into(),
                    voters: vec![],
                },
                PollOption {
                    name: "B".into(),
                    voters: vec![],
                },
            ],
            selectable: 1,
            my_votes: vec![],
        };
        let vote = |names: &[&str]| PollVote {
            chat: "c".into(),
            message_id: "p".into(),
            options: names.iter().map(|s| (*s).to_owned()).collect(),
        };
        assert_eq!(vote(&["B"]).checked(&poll).unwrap(), vec!["B".to_owned()]);
        assert!(vote(&[]).checked(&poll).unwrap().is_empty());
        assert_eq!(
            vote(&["A", "B"]).checked(&poll),
            Err(ChoiceProblem::TooMany { allowed: 1 })
        );
        assert!(!poll.multiple());
    }
}
