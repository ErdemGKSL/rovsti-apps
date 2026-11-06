#![cfg_attr(test, allow(clippy::unwrap_used))]

//! The WhatsApp settings screen every Rovsti app shares.
//!
//! WhatsApp runs on the Rovsti gateway (`services/wa_gateway`). A linked
//! account is an *instance* there, and the screen offers exactly two ways in:
//! link WhatsApp for the first time, or connect to an existing instance with a
//! connect key that another app shows. [`model`] is the pure state machine,
//! [`view`] the GPUI entity that runs its effects, [`qr`] renders pairing codes.
//!
//! [`avatars`] is the shared WhatsApp profile-picture and profile cache
//! (policy in [`avatar_book`]); [`names`] picks what to call a person.

pub mod avatar_book;
pub mod avatars;
pub mod headless;
pub mod model;
pub mod names;
pub mod qr;
pub mod view;

pub use avatars::{WaAvatars, decode_image, photo_avatar};
pub use model::{LINK_FILE, StoredLink};
pub use names::{display_name, display_name_with_profile, number_label};
pub use qr::{QrError, qr_png};
pub use view::WhatsAppSettings;

pub use rovsti_wa_proto::AppId;
