//! Platform-free domain types shared by Rovsti Phone, Messages and Stops.
//!
//! Nothing in this crate touches GPUI, JNI or the network, so it builds and
//! tests on any host in seconds.

pub mod contact;
pub mod phone_number;
pub mod thread;

pub use contact::{Contact, ContactId};
pub use phone_number::{PhoneNumber, PhoneNumberError};
pub use thread::{Channel, Message, MessageDirection, ThreadId};
