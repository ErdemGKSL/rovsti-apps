//! App-level logic of the four apps without UI or JNI; the data dir is always a `&Path` argument.

#![cfg_attr(test, allow(clippy::unwrap_used))]

pub mod cards;
pub mod contacts;
pub mod headless;
pub mod messages;
pub mod people;
pub mod people_sync;
pub mod permission;
pub mod phone;
pub mod stops;
pub mod storage;
pub mod wa_link;
