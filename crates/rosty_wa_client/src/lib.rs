#![cfg_attr(test, allow(clippy::unwrap_used))]

//! Client for the WhatsApp gateway: blocking HTTP calls, a resumable event
//! WebSocket and the call-audio WebSocket.
//!
//! TLS is rustls with the pure-Rust graviola provider; `ring` and `aws-lc-rs`
//! must stay out of this crate's dependency tree so it builds for Android
//! without the NDK.

pub mod audio;
pub mod error;
pub mod events;
pub mod http;
pub mod queue;

pub mod tls;

pub use audio::{CallAudio, call_audio, call_audio_with};
pub use error::ClientError;
pub use events::{ClientEvent, EventStream, subscribe};
pub use http::{Gateway, Picture};
pub use queue::{Closed, Mic, QueueStats, RecvError, Speaker};
