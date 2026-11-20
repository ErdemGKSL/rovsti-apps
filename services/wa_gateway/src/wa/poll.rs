//! Polls in WhatsApp's protobufs (E6-WA R5), pure: reading a poll of any creation form, building the one the gateway
//! sends (as whatsapp-rust 0.7.0 `polls().create` builds it, so the chat's timer can go along), and opening a vote
//! with the poll's message secret.
//!
//! A vote names the options it picked by the SHA-256 of their names, encrypted under a key derived from the secret,
//! the poll id, the poll's author and the voter (`wacore::poll`). Which ids were used depends on how each side
//! addressed the other (number or LID), so a vote is tried under each pair of ids the gateway knows, both of one
//! kind first, as WhatsApp Web does.

use rosty_wa_proto::{Poll, PollOption};
use whatsapp_rust::wacore::poll::{self as wa_poll, PollVoteCiphertext};
use whatsapp_rust::waproto::buffa::MessageField;
use whatsapp_rust::waproto::whatsapp as wa;

/// Length of the message secret WhatsApp generates for a poll.
pub const SECRET_LEN: usize = 32;

/// The poll a message carries, in any of its creation forms (v1, v2, v3, v5, v6), with no votes yet.
pub fn poll_of(base: &wa::Message) -> Option<Poll> {
    let forms = [
        &base.poll_creation_message,
        &base.poll_creation_message_v2,
        &base.poll_creation_message_v3,
        &base.poll_creation_message_v5,
        &base.poll_creation_message_v6,
    ];
    let m = forms.into_iter().find_map(MessageField::as_option)?;
    let options: Vec<PollOption> = m
        .options
        .iter()
        .filter_map(|o| o.option_name.clone())
        .filter(|name| !name.is_empty())
        .map(|name| PollOption {
            name,
            voters: Vec::new(),
        })
        .collect();
    if options.is_empty() {
        return None;
    }
    Some(Poll {
        question: m.name.clone().unwrap_or_default(),
        options,
        selectable: m.selectable_options_count.unwrap_or(0),
        my_votes: Vec::new(),
    })
}

/// The poll message to send: single choice as `pollCreationMessageV3`, multiple as `pollCreationMessage`
/// (whatsapp-rust 0.7.0 `features/polls.rs:85-117`), with `secret` as its message secret. `options` were checked
/// (`rosty_core::wa_rich::check_draft`).
pub fn creation_message(
    question: &str,
    options: &[String],
    multiple: bool,
    secret: Vec<u8>,
    context: Option<wa::ContextInfo>,
) -> wa::Message {
    let selectable = rosty_core::wa_rich::selectable_count(multiple, options.len());
    let poll = wa::message::PollCreationMessage {
        name: Some(question.to_owned()),
        options: options
            .iter()
            .map(|name| wa::message::poll_creation_message::Option {
                option_name: Some(name.clone()),
                option_hash: None,
            })
            .collect(),
        selectable_options_count: Some(selectable),
        context_info: context.map_or_else(MessageField::none, MessageField::some),
        poll_content_type: Some(wa::message::PollContentType::TEXT),
        ..Default::default()
    };
    let mut message = if selectable == 1 {
        wa::Message {
            poll_creation_message_v3: MessageField::some(poll),
            ..Default::default()
        }
    } else {
        wa::Message {
            poll_creation_message: MessageField::some(poll),
            ..Default::default()
        }
    };
    message.message_context_info = MessageField::some(wa::MessageContextInfo {
        message_secret: Some(secret),
        ..Default::default()
    });
    message
}

/// The option names of `hashes` (each the SHA-256 of a name), in the poll's order; hashes of no option are dropped.
pub fn names_of(options: &[String], hashes: &[Vec<u8>]) -> Vec<String> {
    options
        .iter()
        .filter(|name| {
            let hash = wa_poll::compute_option_hash(name);
            hashes.iter().any(|h| h.as_slice() == hash)
        })
        .cloned()
        .collect()
}

/// The ids of one person, bare, number first: what a vote's key may have used.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Ids {
    pub pn: Option<String>,
    pub lid: Option<String>,
}

impl Ids {
    /// Sorts `ids` into their number and LID (other kinds and repeats are dropped).
    pub fn of<'a>(ids: impl IntoIterator<Item = &'a str>) -> Self {
        let mut out = Self::default();
        for id in ids {
            let Some(parsed) = rosty_core::wa_identity::parse(id) else {
                continue;
            };
            let bare = parsed.id.bare();
            match parsed.id.kind() {
                rosty_core::wa_identity::WaIdKind::Pn => {
                    out.pn.get_or_insert(bare);
                }
                rosty_core::wa_identity::WaIdKind::Lid => {
                    out.lid.get_or_insert(bare);
                }
                _ => {}
            }
        }
        out
    }

    fn all(&self) -> impl Iterator<Item = &String> {
        self.pn.iter().chain(self.lid.iter())
    }
}

/// The option names a vote picked, opened with the poll's `secret`. Tries the author's and the voter's ids by
/// number, then by LID, then mixed. `None` when no pair opens it (a wrong secret, an id the gateway never learned).
pub fn open_vote(
    secret: &[u8],
    poll_id: &str,
    creator: &Ids,
    voter: &Ids,
    payload: &[u8],
    iv: &[u8],
    options: &[String],
) -> Option<Vec<String>> {
    let mut pairs: Vec<(&String, &String)> = Vec::new();
    if let (Some(c), Some(v)) = (&creator.pn, &voter.pn) {
        pairs.push((c, v));
    }
    if let (Some(c), Some(v)) = (&creator.lid, &voter.lid) {
        pairs.push((c, v));
    }
    for c in creator.all() {
        for v in voter.all() {
            if !pairs.contains(&(c, v)) {
                pairs.push((c, v));
            }
        }
    }
    pairs.into_iter().find_map(|(c, v)| {
        wa_poll::decrypt_poll_vote_with_secret(
            PollVoteCiphertext {
                enc_payload: payload,
                enc_iv: iv,
            },
            secret,
            poll_id,
            c,
            v,
        )
        .ok()
        .map(|hashes| names_of(options, &hashes))
    })
}

/// `poll` with its options counted from `votes` (everyone's newest vote) and my choice: the newest vote of
/// [`DELETED_BY_ME`](rosty_wa_proto::DELETED_BY_ME), which names my votes from any of my devices.
pub fn counted(poll: &Poll, votes: &[rosty_core::wa_rich::Vote]) -> Poll {
    let names = poll.option_names();
    let options = rosty_core::wa_rich::tally(&names, votes)
        .into_iter()
        .map(|t| PollOption {
            name: t.name,
            voters: t.voters,
        })
        .collect();
    let my_votes = rosty_core::wa_rich::latest_votes(votes)
        .into_iter()
        .find(|v| v.voter == rosty_wa_proto::DELETED_BY_ME)
        .map(|v| v.options)
        .unwrap_or_default();
    Poll {
        options,
        my_votes,
        ..poll.clone()
    }
}

/// A vote that came with a poll in a history sync: already opened, its option hashes in the clear.
pub fn history_vote_names(options: &[String], update: &wa::PollUpdate) -> Option<Vec<String>> {
    let vote = update.vote.as_option()?;
    Some(names_of(options, &vote.selected_options))
}

#[cfg(test)]
mod tests {
    use super::*;

    const POLL: &str = "3EB0POLL";
    const CREATOR: &str = "100000000051@s.whatsapp.net";
    const CREATOR_LID: &str = "777000000000051@lid";
    const VOTER: &str = "100000000052@s.whatsapp.net";
    const VOTER_LID: &str = "777000000000052@lid";

    fn names(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| (*s).to_owned()).collect()
    }

    fn encrypt(picked: &[&str], creator: &str, voter: &str) -> (Vec<u8>, Vec<u8>) {
        let hashes: Vec<Vec<u8>> = picked
            .iter()
            .map(|n| wa_poll::compute_option_hash(n).to_vec())
            .collect();
        let (payload, iv) =
            wa_poll::encrypt_poll_vote_with_secret(&hashes, &[7; SECRET_LEN], POLL, creator, voter)
                .unwrap();
        (payload, iv.to_vec())
    }

    #[test]
    fn every_creation_form_reads_as_a_poll() {
        let built = creation_message(
            "Lunch?",
            &names(&["Pizza", "Soup"]),
            false,
            vec![1; 32],
            None,
        );
        assert!(built.poll_creation_message_v3.as_option().is_some());
        let poll = poll_of(&built).unwrap();
        assert_eq!(poll.question, "Lunch?");
        assert_eq!(poll.option_names(), names(&["Pizza", "Soup"]));
        assert_eq!(poll.selectable, 1);
        assert_eq!(
            built
                .message_context_info
                .as_option()
                .and_then(|c| c.message_secret.clone()),
            Some(vec![1; 32])
        );

        let multi = creation_message(
            "Days",
            &names(&["Mon", "Tue", "Wed"]),
            true,
            vec![1; 32],
            None,
        );
        assert!(multi.poll_creation_message.as_option().is_some());
        assert_eq!(poll_of(&multi).unwrap().selectable, 3);

        let v5 = wa::Message {
            poll_creation_message_v5: MessageField::some(wa::message::PollCreationMessage {
                name: Some("V5".into()),
                options: vec![wa::message::poll_creation_message::Option {
                    option_name: Some("Yes".into()),
                    option_hash: None,
                }],
                ..Default::default()
            }),
            ..Default::default()
        };
        assert_eq!(poll_of(&v5).unwrap().question, "V5");
        assert_eq!(poll_of(&wa::Message::default()), None);
    }

    #[test]
    fn a_vote_opens_under_the_ids_it_was_made_with() {
        let options = names(&["A", "B", "C"]);
        let (payload, iv) = encrypt(&["C", "A"], CREATOR, VOTER);
        let creator = Ids::of([CREATOR, CREATOR_LID]);
        let voter = Ids::of([VOTER]);
        assert_eq!(
            open_vote(
                &[7; SECRET_LEN],
                POLL,
                &creator,
                &voter,
                &payload,
                &iv,
                &options
            ),
            Some(names(&["A", "C"]))
        );
        // A wrong secret opens nothing.
        assert_eq!(
            open_vote(
                &[8; SECRET_LEN],
                POLL,
                &creator,
                &voter,
                &payload,
                &iv,
                &options
            ),
            None
        );
    }

    #[test]
    fn a_lid_only_voter_in_a_lid_poll_opens_by_lid() {
        let options = names(&["A", "B"]);
        let (payload, iv) = encrypt(&["B"], CREATOR_LID, VOTER_LID);
        // The member is known by LID only; the author by both.
        let voter = Ids::of([VOTER_LID]);
        let creator = Ids::of([CREATOR, CREATOR_LID]);
        assert_eq!(
            open_vote(
                &[7; SECRET_LEN],
                POLL,
                &creator,
                &voter,
                &payload,
                &iv,
                &options
            ),
            Some(names(&["B"]))
        );
        // A taken-back vote opens as no names.
        let (empty, iv) = encrypt(&[], CREATOR_LID, VOTER_LID);
        assert_eq!(
            open_vote(
                &[7; SECRET_LEN],
                POLL,
                &creator,
                &voter,
                &empty,
                &iv,
                &options
            ),
            Some(vec![])
        );
    }

    #[test]
    fn ids_sort_numbers_and_lids_and_drop_devices() {
        let ids = Ids::of(["100000000052:4@s.whatsapp.net", VOTER_LID, "g@g.us"]);
        assert_eq!(ids.pn.as_deref(), Some(VOTER));
        assert_eq!(ids.lid.as_deref(), Some(VOTER_LID));
    }
}
