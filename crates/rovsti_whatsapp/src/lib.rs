//! WhatsApp inside Rovsti Messages, with no server of our own.
//!
//! The phone running Rovsti becomes a *linked device* of the user's WhatsApp
//! account (the same multi-device protocol WhatsApp Web uses), via
//! [`whatsapp-rust`](https://github.com/oxidezap/whatsapp-rust). Keys and
//! history live in a SQLite file in the app's private directory.
//!
//! Linking needs the primary WhatsApp app to approve us, and there are two
//! ways to do that:
//!
//! * [`LinkMethod::Qr`]: we render the pairing QR. Since the primary phone
//!   can't scan its own screen, the UI offers a Share button that sends the
//!   QR image to a PC/tablet (or anything with a screen), where the user
//!   scans it from WhatsApp → Linked devices.
//! * [`LinkMethod::PhoneNumber`]: we request an 8-character code; the user
//!   types it in WhatsApp → Linked devices → *Link with phone number
//!   instead*. This works on a single phone with no second screen.
//!
//! Using an unofficial client may violate WhatsApp's Terms of Service; the
//! UI says so before linking.

mod qr;
mod session;

pub use qr::{QrError, qr_png};
pub use session::{LinkMethod, Session, SessionError, WaEvent};
