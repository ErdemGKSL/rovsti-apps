//! Platform-free domain types shared by Rovsti Phone, Messages, Contacts and Stops.
//!
//! Nothing in this crate touches GPUI, JNI or the network, so it builds and
//! tests on any host in seconds.

pub mod audio;
pub mod avatar;
pub mod blocklist;
pub mod call;
pub mod call_log;
pub mod contact;
pub mod contact_card;
pub mod contact_edit;
pub mod contacts_prefs;
pub mod contacts_view;
pub mod dial;
pub mod gesture;
pub mod import_plan;
pub mod inbox;
pub mod lru;
pub mod mms_pdu;
pub mod msg_backup;
pub mod names;
pub mod notify;
pub mod otp;
pub mod perf;
pub mod phone_number;
pub mod recents;
pub mod scene;
pub mod schedule;
pub mod search;
pub mod settings;
pub mod setup;
pub mod sim;
pub mod small_text;
pub mod sms;
pub mod sms_timeline;
pub mod spam;
pub mod speed_dial;
pub mod t9;
pub mod thread;
pub mod vcard;
pub mod wire;

pub use contact::{Contact, ContactId};
pub use phone_number::{PhoneNumber, PhoneNumberError};
pub use thread::{Channel, Message, MessageDirection, ThreadId};
