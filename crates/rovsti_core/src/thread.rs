//! The unified conversation model used by Rovsti Messages: SMS/MMS threads
//! and WhatsApp chats render through the same types.

use crate::PhoneNumber;

/// Where a conversation lives. The UI badges threads by channel and the
/// composer sends through the matching backend.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub enum Channel {
    Sms,
    WhatsApp,
}

#[derive(Clone, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub enum ThreadId {
    /// `Telephony.Threads._ID`.
    Sms(i64),
    /// A WhatsApp JID such as `905321234567@s.whatsapp.net` or `…@g.us`.
    WhatsApp(String),
}

impl ThreadId {
    pub fn channel(&self) -> Channel {
        match self {
            Self::Sms(_) => Channel::Sms,
            Self::WhatsApp(_) => Channel::WhatsApp,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum MessageDirection {
    Incoming,
    Outgoing,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Message {
    pub thread: ThreadId,
    pub direction: MessageDirection,
    /// Sender for incoming messages; `None` for our own.
    pub sender: Option<PhoneNumber>,
    pub body: String,
    /// Unix milliseconds, the unit both Android providers and WhatsApp use.
    pub timestamp_ms: i64,
    pub read: bool,
}
