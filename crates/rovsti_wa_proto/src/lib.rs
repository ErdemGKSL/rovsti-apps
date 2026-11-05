#![cfg_attr(test, allow(clippy::unwrap_used))]

//! Wire and domain types for the WhatsApp gateway.
//!
//! Everything here is pure: no I/O, no clock and no randomness. Callers pass
//! in random bytes and timestamps, so the gateway, the client and the apps
//! share one tested definition of the protocol.

pub mod api;
pub mod audio;
pub mod call;
pub mod event;
pub mod history;
pub mod key;
pub mod limit;
pub mod log;
pub mod push;
pub mod timeline;
pub mod url;

pub use api::*;
pub use call::{
    Arbitration, CallDirection, CallInput, CallMachine, CallPhase, EndReason, WaCallView,
    WaControl, WaScreen, WaScreenPhase, arbitrate,
};
pub use event::{CallUpdate, EventEnvelope, GatewayEvent};
pub use history::{WaCallHistory, WaCallRecord};
pub use key::{ConnectKey, KeyError, KeyHash, KeyId};
pub use log::{EventLog, Since};
pub use push::{Wake, parse_wake};
pub use timeline::{
    Item, ItemState, Timeline, TypingThrottle, day_breaks, reaction_chips, user_jid,
};
pub use url::{GatewayUrl, UrlError};
