//! WhatsApp identity: one person behind a phone-number id and a LID.
//!
//! WhatsApp names the same person by a phone-number JID
//! (`<digits>@s.whatsapp.net`) and by a LID (`<user>@lid`), each with an
//! optional device suffix. This module is the one place that parses those ids
//! ([`parse`]), decides the key a person or chat is stored under
//! ([`canonical_key`]), keeps the table that joins the two forms
//! ([`IdentityMap`]) and picks the name to show ([`display_name`]).
//!
//! No I/O: [`IdentityMap`] is an in-memory model of the `person` and `alias`
//! tables. Every mutating call returns the [`Changes`] to persist and the
//! [`MergeStep`]s that stored rows must follow, and [`IdentityMap::from_rows`]
//! rebuilds the map from what was persisted.

mod id;
mod map;
mod name;
mod rows;
mod source;

#[cfg(test)]
mod map_tests;

pub use id::{IdentityKey, Parsed, WaId, WaIdKind, canonical_key, parse};
pub use map::IdentityMap;
pub use name::{DisplayName, NameInputs, NameTier, display_name};
pub use rows::{
    AliasKind, AliasRow, AliasState, Changes, MergeReason, MergeStep, PersonId, PersonRow,
    RejectReason, Rejected, Resolved,
};
pub use source::Source;
