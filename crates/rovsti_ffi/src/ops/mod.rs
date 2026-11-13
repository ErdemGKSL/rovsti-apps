//! `call(op, request)`: `"<domain>.<verb>"` dispatch. One module per domain.

mod chat;
mod contacts;
mod core;
mod messages;
mod people;
mod phone;
mod qr;
pub mod registry;
mod search;
mod shared;
mod stops;
mod tiles;
mod wa;

pub use tiles::tile_export;
pub use wa::{PULL_WAIT, audio_pull, audio_pull_within, audio_push};

use crate::envelope::FfiError;
use crate::guard::guarded;
use serde_json::Value;

/// Runs one op under the panic guard and encodes the envelope.
pub fn call(op: &str, request: &[u8]) -> Vec<u8> {
    guarded(|| dispatch(op, request))
}

fn dispatch(op: &str, request: &[u8]) -> Result<Value, FfiError> {
    match registry::find(op) {
        Some(spec) => (spec.run)(request),
        None => Err(FfiError::unsupported(format!("unknown op {op:?}"))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::envelope::{Answer, ErrorKind, decode};

    #[test]
    fn unknown_ops_are_unsupported() {
        for op in ["", "nope", "nope.verb", "core.nope", "core"] {
            match decode(&call(op, b"")).unwrap() {
                Answer::Err(e) => assert_eq!(e.kind, ErrorKind::Unsupported, "{op}"),
                other => panic!("{op}: {other:?}"),
            }
        }
    }
}
