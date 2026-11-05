#![cfg_attr(test, allow(clippy::unwrap_used))]

//! The WhatsApp settings screen every Rovsti app shares.
//!
//! WhatsApp runs on the Rovsti gateway (`services/wa_gateway`). A linked
//! account is an *instance* there, and the screen offers exactly two ways in:
//! link WhatsApp for the first time, or connect to an existing instance with a
//! connect key that another app shows. [`model`] is the pure state machine,
//! [`view`] the GPUI entity that runs its effects, [`qr`] renders pairing codes.

pub mod model;
pub mod qr;
pub mod view;

pub use model::{LINK_FILE, StoredLink};
pub use qr::{QrError, qr_png};
pub use view::WhatsAppSettings;

pub use rovsti_wa_proto::AppId;
