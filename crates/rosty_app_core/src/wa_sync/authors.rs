//! The ids a message names besides its chat: its sender, who deleted it, who reacted, the author of the message it
//! quotes and who it mentions. One set of rewrites serves the ingest (an arriving id becomes its canonical key), the
//! backfill (the same for rows stored before the identity tables) and a merge (one key becomes another).

use rosty_wa_proto::{MessageMeta, Quoted, Reaction};
use rusqlite::{Connection, params};

use super::Result;
use super::apply::Extra;

/// Where an id goes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct Moved {
    /// The key to store.
    pub to: String,
    /// The id as it was written, without device.
    pub wire: String,
}

/// Decides, for an id as stored or as it arrived, whether it is rewritten; `None` leaves it.
pub(super) type Rewrite<'a> = dyn FnMut(&str) -> Result<Option<Moved>> + 'a;

/// Rewrites one id. The answer is what it moved to and from, when it moved.
pub(super) fn id(slot: &mut Option<String>, f: &mut Rewrite<'_>) -> Result<Option<Moved>> {
    let Some(text) = slot.as_deref() else {
        return Ok(None);
    };
    let Some(moved) = f(text)? else {
        return Ok(None);
    };
    *slot = Some(moved.to.clone());
    Ok(Some(moved))
}

/// Whether two reactions are one person's: mine, or the same known sender.
fn same_person(a: &Reaction, b: &Reaction) -> bool {
    match (a.from_me, b.from_me) {
        (true, true) => true,
        (false, false) => a.sender.is_some() && a.sender == b.sender,
        _ => false,
    }
}

/// Keeps one reaction per person: the one listed last is the newest. True when one was dropped.
pub(super) fn one_per_person(list: &mut Vec<Reaction>) -> bool {
    let before = list.len();
    let mut kept: Vec<Reaction> = Vec::with_capacity(before);
    for reaction in list.drain(..) {
        kept.retain(|earlier| !same_person(earlier, &reaction));
        kept.push(reaction);
    }
    *list = kept;
    list.len() != before
}

/// Rewrites the senders of a reaction list and leaves one reaction per person. True when the list changed.
pub(super) fn reactions(list: &mut Vec<Reaction>, f: &mut Rewrite<'_>) -> Result<bool> {
    let mut changed = false;
    for reaction in list.iter_mut() {
        changed |= id(&mut reaction.sender, f)?.is_some();
    }
    Ok(one_per_person(list) || changed)
}

/// Rewrites the author of a quoted message. True when it changed.
pub(super) fn quoted(quote: &mut Option<Quoted>, f: &mut Rewrite<'_>) -> Result<bool> {
    match quote {
        Some(quote) => Ok(id(&mut quote.sender, f)?.is_some()),
        None => Ok(false),
    }
}

/// Rewrites mentions and names each person once. True when the list changed.
pub(super) fn mentions(list: &mut Vec<String>, f: &mut Rewrite<'_>) -> Result<bool> {
    let mut changed = false;
    for mention in list.iter_mut() {
        if let Some(moved) = f(mention)? {
            *mention = moved.to;
            changed = true;
        }
    }
    let before = list.len();
    let mut seen: Vec<String> = Vec::with_capacity(before);
    list.retain(|mention| {
        let first = !seen.contains(mention);
        if first {
            seen.push(mention.clone());
        }
        first
    });
    Ok(list.len() != before || changed)
}

/// Rewrites the ids inside a message's meta: the quoted author, the mentions, who deleted it, and the same inside
/// the kept content of a deleted message.
pub(super) fn meta(meta: &mut MessageMeta, f: &mut Rewrite<'_>) -> Result<()> {
    quoted(&mut meta.quoted, f)?;
    mentions(&mut meta.mentions, f)?;
    id(&mut meta.deleted_by, f)?;
    if let Some(kept) = meta.original.as_deref_mut() {
        quoted(&mut kept.meta.quoted, f)?;
        mentions(&mut kept.meta.mentions, f)?;
    }
    Ok(())
}

/// The JSON columns of a message row that name people.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct JsonRefs {
    pub reactions: String,
    pub quoted: Option<String>,
    pub extra: Option<String>,
}

impl JsonRefs {
    /// The columns [`JsonRefs`] reads, in order.
    pub const COLUMNS: &'static str = "reactions, quoted_json, extra_json";

    /// Reads [`Self::COLUMNS`] starting at column `at`.
    pub fn read(row: &rusqlite::Row<'_>, at: usize) -> rusqlite::Result<Self> {
        Ok(Self {
            reactions: row.get(at)?,
            quoted: row.get(at + 1)?,
            extra: row.get(at + 2)?,
        })
    }

    /// The columns after the rewrite; `None` when no id in them moved. A column that does not parse stays as it
    /// is.
    pub fn rewritten(&self, f: &mut Rewrite<'_>) -> Result<Option<Self>> {
        let mut out = self.clone();
        if let Ok(mut list) = serde_json::from_str::<Vec<Reaction>>(&self.reactions)
            && reactions(&mut list, f)?
        {
            out.reactions = serde_json::to_string(&list)?;
        }
        if let Some(text) = &self.quoted
            && let Ok(mut quote) = serde_json::from_str::<Option<Quoted>>(text)
            && quoted(&mut quote, f)?
        {
            out.quoted = Some(serde_json::to_string(&quote)?);
        }
        if let Some(text) = &self.extra
            && let Ok(mut extra) = serde_json::from_str::<Extra>(text)
            && mentions(&mut extra.mentions, f)?
        {
            out.extra = Some(serde_json::to_string(&extra)?);
        }
        Ok((out != *self).then_some(out))
    }

    /// Writes the columns of message `id` of `chat`.
    pub fn write(&self, conn: &Connection, chat: &str, id: &str) -> Result<()> {
        conn.prepare_cached(
            "UPDATE messages SET reactions = ?3, quoted_json = ?4, extra_json = ?5 WHERE chat = ?1 AND id = ?2",
        )?
        .execute(params![chat, id, self.reactions, self.quoted, self.extra])?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const LID: &str = "777000000000001@lid";
    const PN: &str = "905550000001@s.whatsapp.net";

    fn to_pn(text: &str) -> Result<Option<Moved>> {
        Ok((text == LID).then(|| Moved {
            to: PN.to_owned(),
            wire: LID.to_owned(),
        }))
    }

    fn reaction(sender: Option<&str>, from_me: bool, emoji: &str) -> Reaction {
        Reaction {
            sender: sender.map(str::to_owned),
            from_me,
            emoji: emoji.to_owned(),
        }
    }

    #[test]
    fn one_person_keeps_the_reaction_listed_last() {
        let mut list = vec![
            reaction(Some(PN), false, "a"),
            reaction(None, true, "mine"),
            reaction(Some(LID), false, "b"),
            reaction(None, false, "unknown one"),
            reaction(None, false, "unknown two"),
        ];
        assert!(reactions(&mut list, &mut to_pn).unwrap());
        assert_eq!(
            list,
            [
                reaction(None, true, "mine"),
                reaction(Some(PN), false, "b"),
                reaction(None, false, "unknown one"),
                reaction(None, false, "unknown two"),
            ]
        );
        // Nothing left to do.
        assert!(!reactions(&mut list, &mut to_pn).unwrap());
    }

    #[test]
    fn mentions_name_a_person_once() {
        let mut list = vec![PN.to_owned(), "other@lid".to_owned(), LID.to_owned()];
        assert!(mentions(&mut list, &mut to_pn).unwrap());
        assert_eq!(list, [PN, "other@lid"]);
        assert!(!mentions(&mut list, &mut to_pn).unwrap());
    }

    #[test]
    fn json_columns_change_only_where_an_id_moved() {
        let refs = JsonRefs {
            reactions: format!(r#"[{{"sender":"{LID}","from_me":false,"emoji":"x"}}]"#),
            quoted: Some(format!(r#"{{"id":"Q","sender":"{LID}","preview":"hi"}}"#)),
            extra: Some("not json".to_owned()),
        };
        let out = refs.rewritten(&mut to_pn).unwrap().expect("changed");
        assert_eq!(
            out.reactions,
            format!(r#"[{{"sender":"{PN}","from_me":false,"emoji":"x"}}]"#)
        );
        assert_eq!(
            out.quoted.as_deref(),
            Some(format!(r#"{{"id":"Q","sender":"{PN}","preview":"hi"}}"#).as_str())
        );
        assert_eq!(out.extra.as_deref(), Some("not json"));
        assert_eq!(out.rewritten(&mut to_pn).unwrap(), None);
    }
}
