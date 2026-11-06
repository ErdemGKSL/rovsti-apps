//! Android system integration shared by the Rovsti apps.
//!
//! GPUI (through `gpui_android`) owns the window, input and rendering. What
//! it does not cover — default dialer/SMS roles, telephony providers, OEM
//! battery settings — lives in a small Kotlin class, `dev.rovsti.bridge.RovstiBridge`
//! (`kotlin/` in this crate), which each app copies into its ejected Gradle
//! project. Rust calls its static methods through [`bridge`].
//!
//! Every function also compiles on desktop, where it returns
//! [`Error::Unsupported`], so the apps run under `cargo run` for UI work.

pub mod blocklist;
pub mod bridge;
pub mod calls;
pub mod contacts_edit;
pub mod diagnostics;
pub mod documents;
pub mod events;
pub mod handoff;
pub mod headless;
pub mod location;
pub mod media;
pub mod notify;
pub mod perf;
pub mod permissions;
pub mod providers;
pub mod roles;
pub mod settings;
pub mod sms;
pub mod telecom;
pub mod trip;
pub mod wa;
pub mod window;
pub mod xiaomi;

#[cfg(target_os = "android")]
pub use jni;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("not available on this platform")]
    Unsupported,
    #[error("RovstiBridge.nativeInit has not run; is the Kotlin bridge packaged?")]
    NotInitialized,
    #[error(transparent)]
    Frame(#[from] rovsti_wa_proto::audio::FrameError),
    #[cfg(target_os = "android")]
    #[error(transparent)]
    Jni(#[from] jni::errors::Error),
}

pub type Result<T, E = Error> = std::result::Result<T, E>;
