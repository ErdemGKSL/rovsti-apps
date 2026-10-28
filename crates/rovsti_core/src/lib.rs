//! Platform-free domain types shared by Rovsti Phone, Messages and Stops.
//!
//! Nothing in this crate touches GPUI, JNI or the network, so it builds and
//! tests on any host in seconds.

pub mod audio;
pub mod avatar;
pub mod blocklist;
pub mod call;
pub mod call_log;
pub mod contact;
pub mod contacts_view;
pub mod dial;
pub mod lru;
pub mod names;
pub mod perf;
pub mod phone_number;
pub mod recents;
pub mod scene;
pub mod search;
pub mod settings;
pub mod setup;
pub mod small_text;
pub mod thread;
pub mod wire;

pub use contact::{Contact, ContactId};
pub use phone_number::{PhoneNumber, PhoneNumberError};
pub use thread::{Channel, Message, MessageDirection, ThreadId};
