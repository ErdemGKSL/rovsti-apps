//! `call(op, request)`: `"<domain>.<verb>"` dispatch. One module per domain.

mod core;
pub mod registry;
mod shared;

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
