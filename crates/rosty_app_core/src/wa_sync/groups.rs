//! Groups on the device (E6-WA R6): the group's metadata and its members, from the gateway's answer
//! ([`Store::group_store`]), kept current between fetches by the notice lines of group changes (a stored
//! `SystemNote::Group` merges into the members through `rosty_core::wa_group::merge`) and by `chat_meta {left}`.
//! The info page reads it at once and offline ([`Store::group_read`]): the members in pages, me first, then the
//! admins, then by name, so a group of a thousand people scrolls without loading them all.

use rosty_core::wa_group::{
    self, Change, GroupSettings, GroupView, Member, Role, group_view, my_role, order_members,
};
use rosty_wa_proto::{GroupInfo, SystemNote};
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};

use super::{Result, Store};

/// The most members one [`Store::group_read`] answers.
pub const MAX_MEMBER_PAGE: u32 = 500;

/// What the device keeps about a group besides its members.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredGroup {
    pub jid: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub subject: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub created_ms: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub creator: Option<String>,
    pub settings: GroupSettings,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ephemeral_s: Option<u32>,
    /// How many members WhatsApp counts (may be more than the stored list).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub size: Option<u32>,
    /// `parent`, `announcement`, `general` or `sub`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub community_kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub community_parent: Option<String>,
    /// When the gateway last answered for it (0: only notes and the left flag are known).
    pub fetched_ms: i64,
}

/// What [`Store::group_store`] changed.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct GroupStored {
    pub members: u32,
}

/// A page of a group for the info page.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct GroupPage {
    /// `None` before the group was ever fetched.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub group: Option<StoredGroup>,
    /// This page of members.
    pub members: Vec<Member>,
    /// Members that match the filter.
    pub total: u32,
    /// Admins (super admin included) of the whole list.
    pub admins: u32,
    /// My role and what I may do.
    pub view: GroupView,
}

fn flag(on: bool) -> i64 {
    i64::from(on)
}

fn read_group(conn: &Connection, jid: &str) -> Result<Option<StoredGroup>> {
    Ok(conn
        .query_row(
            "SELECT subject, description, description_id, created_ms, creator, announce, locked, approval,
                admins_add, gone, ephemeral_s, size, community_kind, community_parent, fetched_ms
             FROM groups WHERE jid = ?1",
            [jid],
            |r| {
                Ok(StoredGroup {
                    jid: jid.to_owned(),
                    subject: r.get(0)?,
                    description: r.get(1)?,
                    description_id: r.get(2)?,
                    created_ms: r.get(3)?,
                    creator: r.get(4)?,
                    settings: GroupSettings {
                        announce: r.get(5)?,
                        locked: r.get(6)?,
                        approval: r.get(7)?,
                        admins_add: r.get(8)?,
                        left: r.get(9)?,
                    },
                    ephemeral_s: r.get(10)?,
                    size: r.get(11)?,
                    community_kind: r.get(12)?,
                    community_parent: r.get(13)?,
                    fetched_ms: r.get(14)?,
                })
            },
        )
        .optional()?)
}

fn read_members(conn: &Connection, jid: &str) -> Result<Vec<Member>> {
    let mut stmt =
        conn.prepare("SELECT jid, alt, role, name FROM group_members WHERE group_jid = ?1")?;
    let rows = stmt.query_map([jid], |r| {
        let role: String = r.get(2)?;
        Ok(Member {
            jid: r.get(0)?,
            alt: r.get(1)?,
            role: Role::from_wire(Some(&role), false),
            name: r.get(3)?,
        })
    })?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

fn write_members(conn: &Connection, jid: &str, members: &[Member]) -> Result<()> {
    conn.execute("DELETE FROM group_members WHERE group_jid = ?1", [jid])?;
    let mut stmt = conn.prepare(
        "INSERT OR REPLACE INTO group_members (group_jid, jid, alt, role, name) VALUES (?1, ?2, ?3, ?4, ?5)",
    )?;
    for m in members {
        stmt.execute(params![jid, m.jid, m.alt, m.role.wire(), m.name])?;
    }
    Ok(())
}

/// A group of the store, with only its jid when it was never fetched.
fn ensure_group(conn: &Connection, jid: &str) -> Result<()> {
    conn.execute(
        "INSERT OR IGNORE INTO groups (jid, fetched_ms) VALUES (?1, 0)",
        [jid],
    )?;
    Ok(())
}

/// The left flag of `chat_meta` (a group I left or was removed from, or am in again).
pub(super) fn set_left(conn: &Connection, jid: &str, left: bool) -> Result<()> {
    if !jid.ends_with("@g.us") {
        return Ok(());
    }
    if left {
        ensure_group(conn, jid)?;
    }
    conn.execute(
        "UPDATE groups SET gone = ?2 WHERE jid = ?1",
        params![jid, flag(left)],
    )?;
    Ok(())
}

/// The member change a notice line says, if any.
fn change_of(note: &SystemNote) -> Option<Change> {
    let SystemNote::Group {
        action,
        participants,
        ..
    } = note
    else {
        return None;
    };
    let jids = participants.clone();
    Some(match action.as_str() {
        "add" | "join_link" => Change::Add {
            members: jids.iter().map(|j| Member::of(j, Role::Member)).collect(),
        },
        "remove" | "leave" => Change::Remove { jids },
        "promote" => Change::Promote { jids },
        "demote" => Change::Demote { jids },
        _ => return None,
    })
}

/// A stored notice line of a group: its member change merges into the stored members when it is newer than the
/// last fetch (an older line of a history page changes nothing), and a subject, description or setting line moves
/// the stored group along.
pub(super) fn apply_note(conn: &Connection, chat: &str, note: &SystemNote, ts: i64) -> Result<()> {
    let Some(group) = read_group(conn, chat)? else {
        return Ok(());
    };
    if ts <= group.fetched_ms {
        return Ok(());
    }
    let SystemNote::Group { action, value, .. } = note else {
        return Ok(());
    };
    if let Some(change) = change_of(note) {
        let mut members = read_members(conn, chat)?;
        if wa_group::merge(&mut members, &change) > 0 {
            write_members(conn, chat, &members)?;
        }
    }
    let on = value.as_deref() != Some("off");
    let column = match action.as_str() {
        "announce" => Some("announce"),
        "locked" => Some("locked"),
        "approval" => Some("approval"),
        _ => None,
    };
    if let Some(column) = column {
        conn.execute(
            &format!("UPDATE groups SET {column} = ?2 WHERE jid = ?1"),
            params![chat, flag(on)],
        )?;
    }
    match action.as_str() {
        "subject" => {
            conn.execute(
                "UPDATE groups SET subject = ?2 WHERE jid = ?1",
                params![chat, value],
            )?;
        }
        "description" => {
            let text = value.as_deref().filter(|v| *v != "removed");
            conn.execute(
                "UPDATE groups SET description = ?2, description_id = NULL WHERE jid = ?1",
                params![chat, text],
            )?;
        }
        _ => {}
    }
    Ok(())
}

/// A member as the gateway names them: the id it lists them under, the other id next to it.
fn member_of(p: &rosty_wa_proto::Participant) -> Member {
    let alt = [p.pn.as_deref(), p.lid.as_deref()]
        .into_iter()
        .flatten()
        .find(|other| !wa_group::same_id(other, &p.jid))
        .map(str::to_owned);
    Member {
        jid: p.jid.clone(),
        alt,
        role: Role::from_wire(p.role.as_deref(), p.admin),
        name: p.name.clone().filter(|n| !n.trim().is_empty()),
    }
}

impl Store {
    /// Keeps the gateway's answer for a group (`wa.group`) at `now_ms`: its metadata and its members, replacing
    /// what was stored. An answer for a group I left keeps what was known and marks it left.
    pub fn group_store(&mut self, info: &GroupInfo, now_ms: i64) -> Result<GroupStored> {
        let tx = self.conn.transaction()?;
        let jid = info.jid.as_str();
        ensure_group(&tx, jid)?;
        if info.left && info.participants.is_empty() {
            tx.execute("UPDATE groups SET gone = 1 WHERE jid = ?1", [jid])?;
            tx.commit()?;
            return Ok(GroupStored::default());
        }
        tx.execute(
            "UPDATE groups SET subject = ?2, description = ?3, description_id = ?4, created_ms = ?5, creator = ?6,
                announce = ?7, locked = ?8, approval = ?9, admins_add = ?10, gone = ?11, ephemeral_s = ?12,
                size = ?13, community_kind = ?14, community_parent = ?15, fetched_ms = ?16
             WHERE jid = ?1",
            params![
                jid,
                Some(info.subject.as_str()).filter(|s| !s.is_empty()),
                info.description,
                info.description_id,
                info.created_ms,
                info.creator,
                flag(info.announce),
                flag(info.locked),
                flag(info.approval),
                flag(info.admins_add),
                flag(info.left),
                info.ephemeral_s,
                info.size,
                info.community.as_ref().map(|c| c.kind.as_str()),
                info.community.as_ref().and_then(|c| c.parent.as_deref()),
                now_ms,
            ],
        )?;
        let members: Vec<Member> = info.participants.iter().map(member_of).collect();
        write_members(&tx, jid, &members)?;
        tx.commit()?;
        Ok(GroupStored {
            members: u32::try_from(members.len()).unwrap_or(u32::MAX),
        })
    }

    /// The group `jid` for the info page: its metadata, `limit` members from `offset` (at most
    /// [`MAX_MEMBER_PAGE`]) in the page order (me first, admins, then by WhatsApp name) filtered by `query` (a
    /// name or digits of a number), and my role among `own` (my number and LID).
    pub fn group_read(
        &self,
        jid: &str,
        own: &[String],
        query: Option<&str>,
        offset: u32,
        limit: u32,
    ) -> Result<GroupPage> {
        let group = read_group(&self.conn, jid)?;
        let mut members = read_members(&self.conn, jid)?;
        let settings = group.as_ref().map(|g| g.settings).unwrap_or_default();
        let me = my_role(&members, own);
        let admins = members.iter().filter(|m| m.role.is_admin()).count();
        let view = group_view(me, &settings, admins, members.len());
        order_members(&mut members, own, |m| m.name.clone());
        if let Some(query) = query.map(str::trim).filter(|q| !q.is_empty()) {
            let lower = query.to_lowercase();
            // Digits search the numbers only when the query is a number (no letters).
            let digits: String = if query.chars().any(char::is_alphabetic) {
                String::new()
            } else {
                query.chars().filter(char::is_ascii_digit).collect()
            };
            members.retain(|m| {
                m.name
                    .as_deref()
                    .is_some_and(|n| n.to_lowercase().contains(&lower))
                    || (!digits.is_empty()
                        && std::iter::once(m.jid.as_str())
                            .chain(m.alt.as_deref())
                            .any(|id| id.split('@').next().unwrap_or_default().contains(&digits)))
            });
        }
        let total = u32::try_from(members.len()).unwrap_or(u32::MAX);
        let page: Vec<Member> = members
            .into_iter()
            .skip(offset as usize)
            .take(limit.min(MAX_MEMBER_PAGE) as usize)
            .collect();
        Ok(GroupPage {
            group,
            members: page,
            total,
            admins: u32::try_from(admins).unwrap_or(u32::MAX),
            view,
        })
    }
}
