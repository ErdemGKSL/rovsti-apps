//! The WhatsApp settings state machine lives in `rosty_app_core` now (the Kotlin apps drive it
//! through the `wa.settings_apply` op); the GPUI screen keeps using it under this path.

pub use rosty_app_core::wa_link::settings::*;
