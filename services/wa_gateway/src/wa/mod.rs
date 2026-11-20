//! The whatsapp-rust driver: one `Bot` per instance, all in this process.

pub mod app_state;
pub mod call_audio;
pub mod calls;
pub mod convert;
pub mod groups;
pub mod history;
pub mod lid;
pub mod media;
pub mod picture;
pub mod poll;
pub mod reaction;
pub mod rich;
pub mod session;
pub mod status;

pub use session::WaRuntime;
