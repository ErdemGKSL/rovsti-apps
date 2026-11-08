//! `call(op, request)`: `"<domain>.<verb>"` dispatch. One module per domain.

mod core;

use crate::envelope::FfiError;
use crate::guard::guarded;
use serde_json::Value;

/// Runs one op under the panic guard and encodes the envelope.
pub fn call(op: &str, request: &[u8]) -> Vec<u8> {
    guarded(|| dispatch(op, request))
}

fn dispatch(op: &str, request: &[u8]) -> Result<Value, FfiError> {
    let (domain, verb) = op.split_once('.').unwrap_or((op, ""));
    match domain {
        "core" => core::run(verb, request),
        _ => Err(FfiError::unsupported(format!("unknown op {op:?}"))),
    }
}

/// Parses a request body; an empty body is `null`.
pub(crate) fn parse(request: &[u8]) -> Result<Value, FfiError> {
    if request.is_empty() {
        return Ok(Value::Null);
    }
    Ok(serde_json::from_slice(request)?)
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
