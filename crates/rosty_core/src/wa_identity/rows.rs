//! The rows of the `person` and `alias` tables and what a call changed.

use serde::{Deserialize, Serialize};

use super::id::{IdentityKey, WaId, WaIdKind};
use super::source::Source;

/// The stable handle of a person: the `person.person_id` column. It survives
/// a key change, and is never reused after a merge removed it.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct PersonId(pub i64);

/// Which form of id an alias is: the `alias.kind` column.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AliasKind {
    /// A phone number.
    Pn,
    /// A LID.
    Lid,
}

impl AliasKind {
    /// The stored name: `pn` or `lid`.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Pn => "pn",
            Self::Lid => "lid",
        }
    }

    /// The kind of a stored name.
    #[must_use]
    pub fn from_stored(name: &str) -> Option<Self> {
        [Self::Pn, Self::Lid]
            .into_iter()
            .find(|kind| kind.as_str() == name)
    }
}

/// Whether an alias is the person's id in use or an earlier one: the
/// `alias.state` column.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AliasState {
    /// The number or LID in use. A person has at most one of each kind.
    Current,
    /// An earlier number or LID that still resolves to the person.
    Former,
}

impl AliasState {
    /// The stored name: `current` or `former`.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Current => "current",
            Self::Former => "former",
        }
    }

    /// The state of a stored name.
    #[must_use]
    pub fn from_stored(name: &str) -> Option<Self> {
        [Self::Current, Self::Former]
            .into_iter()
            .find(|state| state.as_str() == name)
    }
}

/// A row of the `person` table.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct PersonRow {
    /// The stable handle.
    pub person_id: PersonId,
    /// The canonical key, unique among persons.
    pub key: IdentityKey,
}

/// A row of the `alias` table, primary key `(kind, user)`.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AliasRow {
    /// Phone number or LID.
    pub kind: AliasKind,
    /// The digits of the number, or the LID user.
    pub user: String,
    /// The person the alias belongs to.
    pub person_id: PersonId,
    /// In use or earlier.
    pub state: AliasState,
    /// The strongest source that named the alias.
    pub source: Source,
    /// When that source named it, in Unix milliseconds; 0 for an id that was
    /// only seen on its own.
    pub learned_ms: i64,
}

/// Why stored rows move from one key to another: the `merge_log.reason`
/// column.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MergeReason {
    /// A LID and a phone number were learned to be one person.
    Mapped,
    /// A person moved to a new phone number.
    NumberChange,
}

impl MergeReason {
    /// The stored name: `mapped` or `number_change`.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Mapped => "mapped",
            Self::NumberChange => "number_change",
        }
    }

    /// The reason of a stored name.
    #[must_use]
    pub fn from_stored(name: &str) -> Option<Self> {
        [Self::Mapped, Self::NumberChange]
            .into_iter()
            .find(|reason| reason.as_str() == name)
    }
}

/// Rows stored under `from_key` now belong under `into_key`.
///
/// Produced whenever the key of a person the map already knew changes: a LID
/// person gains its number, two persons become one, a number changes. Steps
/// are applied in the order they are listed.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct MergeStep {
    /// The key that no longer names a person.
    pub from_key: IdentityKey,
    /// The key that person is now stored under.
    pub into_key: IdentityKey,
    /// Why.
    pub reason: MergeReason,
}

/// Why a pair changed nothing.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RejectReason {
    /// An id did not parse, or was not the kind the call takes.
    Invalid,
    /// The LID already belongs to another number by a stronger or newer
    /// source.
    Outranked,
    /// The pair would join the account's own number or LID to someone else.
    OwnId,
}

/// A pair that changed nothing, for the log.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Rejected {
    /// The LID as given; empty when a number change named none.
    pub lid: String,
    /// The phone-number id as given (the new one for a number change).
    pub pn: String,
    /// The source of the pair.
    pub source: Source,
    /// Why it was not applied.
    pub reason: RejectReason,
}

/// What one call changed, for the persistence layer.
///
/// Apply in one transaction: upsert `persons`, upsert `aliases`, delete
/// `removed_persons`, then append `merges` to the merge log in order. An
/// alias row is never deleted, it only moves between persons.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Changes {
    /// Person rows to insert or update.
    pub persons: Vec<PersonRow>,
    /// Alias rows to insert or update.
    pub aliases: Vec<AliasRow>,
    /// Persons merged into another one: delete their rows.
    pub removed_persons: Vec<PersonId>,
    /// Key changes that stored rows must follow, oldest first.
    pub merges: Vec<MergeStep>,
    /// Pairs that were not applied.
    pub rejected: Vec<Rejected>,
}

impl Changes {
    /// Whether the call changed nothing and rejected nothing.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.persons.is_empty()
            && self.aliases.is_empty()
            && self.removed_persons.is_empty()
            && self.merges.is_empty()
            && self.rejected.is_empty()
    }

    /// Adds the changes of a later call, so several calls persist as one.
    /// The later row of a person or alias replaces the earlier one.
    pub fn append(&mut self, later: Changes) {
        for row in later.persons {
            self.persons.retain(|p| p.person_id != row.person_id);
            self.persons.push(row);
        }
        for row in later.aliases {
            self.aliases
                .retain(|a| (a.kind, &a.user) != (row.kind, &row.user));
            self.aliases.push(row);
        }
        for id in later.removed_persons {
            self.persons.retain(|p| p.person_id != id);
            if !self.removed_persons.contains(&id) {
                self.removed_persons.push(id);
            }
        }
        self.merges.extend(later.merges);
        self.rejected.extend(later.rejected);
    }
}

/// An id resolved against the map.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Resolved {
    /// The person behind a phone number or LID. `None` for a group, a
    /// broadcast list, the status feed, a channel, a bot or any other kind
    /// (they are never persons), and from [`IdentityMap::lookup`] for a
    /// number or LID the map has not seen.
    pub person_id: Option<PersonId>,
    /// The key rows are stored under: the person's canonical key, or the
    /// bare id of a kind that is not a person.
    pub key: IdentityKey,
    /// The kind of the id as it was written.
    pub kind: WaIdKind,
    /// The id as it was written, without device: what goes back on the wire.
    pub wire: WaId,
    /// The device suffix of the text.
    pub device: Option<u16>,
    /// The person and alias rows created for an id seen for the first time;
    /// empty otherwise.
    pub changes: Changes,
}
