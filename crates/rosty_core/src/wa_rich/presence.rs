//! The presence line under a chat's title: typing or recording, online, or last seen.
//!
//! WhatsApp sends "composing" and "paused" chat states; a "paused" that is lost would leave "typing…" up for good,
//! so a typing state ends on its own after [`TYPING_TTL_MS`]. Last seen comes only when the person shares it; a
//! hidden last seen shows nothing. Groups show who is typing or recording, never anyone's online state.

use serde::{Deserialize, Serialize};

/// A typing or recording state that was not renewed this long ago is over (WhatsApp clients repeat it while the
/// person keeps typing).
pub const TYPING_TTL_MS: i64 = 25_000;

/// What someone is doing in the chat.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TypingKind {
    Typing,
    Recording,
}

/// A typing or recording state as it arrived.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TypingSeen {
    pub jid: String,
    pub kind: TypingKind,
    pub at_ms: i64,
}

/// The newest presence of the other person of a one-to-one chat.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PresenceSeen {
    pub available: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_seen_ms: Option<i64>,
}

/// The line the header shows.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum HeaderPresence {
    /// Nothing to show (unknown, hidden, or a group with nobody typing).
    None,
    Online,
    LastSeen {
        at_ms: i64,
    },
    /// Who is typing (one in a one-to-one chat), oldest first.
    Typing {
        who: Vec<String>,
    },
    Recording {
        who: Vec<String>,
    },
    /// Some type and some record (groups).
    Busy {
        typing: Vec<String>,
        recording: Vec<String>,
    },
}

/// The header line of a chat at `now_ms`. Typing beats recording beats online beats last seen; in a group only
/// typing and recording count. A last seen in the future (a clock off) is shown as now.
pub fn header_presence(
    group: bool,
    presence: Option<PresenceSeen>,
    typing: &[TypingSeen],
    now_ms: i64,
) -> HeaderPresence {
    let live: Vec<&TypingSeen> = typing
        .iter()
        .filter(|t| now_ms - t.at_ms < TYPING_TTL_MS && t.at_ms - now_ms < TYPING_TTL_MS)
        .collect();
    let mut typers: Vec<String> = Vec::new();
    let mut recorders: Vec<String> = Vec::new();
    for t in &live {
        let list = match t.kind {
            TypingKind::Typing => &mut typers,
            TypingKind::Recording => &mut recorders,
        };
        if !list.contains(&t.jid) {
            list.push(t.jid.clone());
        }
    }
    match (typers.is_empty(), recorders.is_empty()) {
        (false, false) if group => {
            return HeaderPresence::Busy {
                typing: typers,
                recording: recorders,
            };
        }
        (false, _) => return HeaderPresence::Typing { who: typers },
        (true, false) => return HeaderPresence::Recording { who: recorders },
        (true, true) => {}
    }
    if group {
        return HeaderPresence::None;
    }
    match presence {
        Some(PresenceSeen {
            available: true, ..
        }) => HeaderPresence::Online,
        Some(PresenceSeen {
            last_seen_ms: Some(at),
            ..
        }) if at > 0 => HeaderPresence::LastSeen {
            at_ms: at.min(now_ms),
        },
        _ => HeaderPresence::None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_800_000_000_000;
    const ALI: &str = "100000000001@s.whatsapp.net";
    const BEN: &str = "100000000002@s.whatsapp.net";

    fn seen(jid: &str, kind: TypingKind, ago: i64) -> TypingSeen {
        TypingSeen {
            jid: jid.to_owned(),
            kind,
            at_ms: NOW - ago,
        }
    }

    #[test]
    fn typing_beats_online_and_ends_on_its_own() {
        let online = Some(PresenceSeen {
            available: true,
            last_seen_ms: None,
        });
        assert_eq!(
            header_presence(false, online, &[seen(ALI, TypingKind::Typing, 1_000)], NOW),
            HeaderPresence::Typing {
                who: vec![ALI.into()]
            }
        );
        assert_eq!(
            header_presence(
                false,
                online,
                &[seen(ALI, TypingKind::Typing, TYPING_TTL_MS)],
                NOW
            ),
            HeaderPresence::Online
        );
        assert_eq!(
            header_presence(false, None, &[seen(ALI, TypingKind::Recording, 0)], NOW),
            HeaderPresence::Recording {
                who: vec![ALI.into()]
            }
        );
    }

    #[test]
    fn last_seen_shows_only_when_shared() {
        let at = NOW - 3_600_000;
        assert_eq!(
            header_presence(
                false,
                Some(PresenceSeen {
                    available: false,
                    last_seen_ms: Some(at)
                }),
                &[],
                NOW
            ),
            HeaderPresence::LastSeen { at_ms: at }
        );
        // Hidden: WhatsApp sends no time.
        let hidden = Some(PresenceSeen {
            available: false,
            last_seen_ms: None,
        });
        assert_eq!(
            header_presence(false, hidden, &[], NOW),
            HeaderPresence::None
        );
        assert_eq!(header_presence(false, None, &[], NOW), HeaderPresence::None);
        // A time ahead of the phone's clock reads as now.
        let ahead = Some(PresenceSeen {
            available: false,
            last_seen_ms: Some(NOW + 60_000),
        });
        assert_eq!(
            header_presence(false, ahead, &[], NOW),
            HeaderPresence::LastSeen { at_ms: NOW }
        );
    }

    #[test]
    fn a_group_shows_typists_never_online() {
        let online = Some(PresenceSeen {
            available: true,
            last_seen_ms: None,
        });
        assert_eq!(
            header_presence(true, online, &[], NOW),
            HeaderPresence::None
        );
        let states = [
            seen(ALI, TypingKind::Typing, 3_000),
            seen(BEN, TypingKind::Recording, 1_000),
            seen(ALI, TypingKind::Typing, 1_000),
        ];
        assert_eq!(
            header_presence(true, None, &states, NOW),
            HeaderPresence::Busy {
                typing: vec![ALI.into()],
                recording: vec![BEN.into()]
            }
        );
        assert_eq!(
            header_presence(true, None, &states[..1], NOW),
            HeaderPresence::Typing {
                who: vec![ALI.into()]
            }
        );
    }
}
