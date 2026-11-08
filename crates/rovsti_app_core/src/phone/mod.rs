//! Phone logic without UI or Android: per-call notes and WhatsApp calls.
//!
//! Phone's preferences (`PrefsV2`) already live in `rovsti_core::settings` and
//! `crate::storage`; the backup glue moved with them.

pub mod notes;
pub mod wa_call;
