//! The JNI library of the Kotlin apps (`librovsti_ffi.so`).
//!
//! Kotlin talks to Rust through eight exports on `dev.rovsti.core.RustNative`
//! ([`jni_exports`]). Every export runs inside a panic guard ([`guard`]) and answers with a
//! JSON envelope ([`envelope`]). Stateful Rust objects live in a generational handle
//! registry ([`handles`]); Rust pushes events to Kotlin through one sink ([`events`]).
//! The exports are compiled on every target so a host build of the library is real.

#![cfg_attr(test, allow(clippy::unwrap_used))]

pub mod envelope;
pub mod events;
pub mod guard;
pub mod handles;
pub mod jni_exports;
pub mod logger;
pub mod ops;
pub mod state;
pub mod tile_buffer;

/// Version of the Kotlin/Rust contract; bumped when an export changes shape.
pub const ABI: u32 = 1;
