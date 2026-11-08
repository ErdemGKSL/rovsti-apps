//! `core.*`: ops about the bridge itself.

use super::registry::{Case, OpSpec};
use crate::envelope::{ErrorKind, FfiError};
use crate::op;
use crate::state;
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!("core.version", Value, Value, version, version_cases),
    op!("core.echo", Value, Value, echo, echo_cases),
    op!("core.data_dir", Value, Value, data_dir, data_dir_cases),
    op!("core.fail", Value, Value, fail, fail_cases),
    // Test-only ops: reachable in unit tests and with the `test-ops` feature, never fixtures.
    #[cfg(any(test, feature = "test-ops"))]
    op!("core.panic", Value, Value, panic_op, no_cases),
    #[cfg(any(test, feature = "test-ops"))]
    op!("core.emit_test", Value, Value, emit_test, no_cases),
];

fn version(_: Value) -> Result<Value, FfiError> {
    Ok(state::version())
}

fn version_cases() -> Vec<Case> {
    vec![Case::new(Value::Null)]
}

fn echo(body: Value) -> Result<Value, FfiError> {
    Ok(body)
}

fn echo_cases() -> Vec<Case> {
    vec![Case::new(json!({
        "text": "h\u{e9}llo \u{1f30d}",
        "numbers": [1, 2, 3],
        "nested": {"flag": true, "note": null},
    }))]
}

fn data_dir(_: Value) -> Result<Value, FfiError> {
    let dirs = state::dirs().ok_or_else(|| FfiError::invalid("init was not called"))?;
    Ok(json!({
        "data_dir": dirs.data_dir,
        "cache_dir": dirs.cache_dir,
        "app_id": dirs.app_id,
        "sdk": dirs.sdk,
    }))
}

fn data_dir_cases() -> Vec<Case> {
    vec![Case::new(Value::Null)]
}

/// {"kind": "io", "message": "...", "detail": {...}}: an error of the asked kind.
fn fail(body: Value) -> Result<Value, FfiError> {
    let name = body
        .get("kind")
        .and_then(Value::as_str)
        .unwrap_or("invalid");
    let kind = ErrorKind::from_name(name)
        .ok_or_else(|| FfiError::invalid(format!("unknown error kind {name:?}")))?;
    let message = body
        .get("message")
        .and_then(Value::as_str)
        .unwrap_or("requested failure");
    let mut error = FfiError::new(kind, message);
    error.detail = body.get("detail").cloned();
    Err(error)
}

/// One fixture per error kind (`core.fail.<kind>`).
fn fail_cases() -> Vec<Case> {
    ErrorKind::ALL
        .into_iter()
        .map(|kind| {
            let mut request = json!({
                "kind": kind.name(),
                "message": format!("requested {} failure", kind.name()),
            });
            if kind == ErrorKind::Io {
                request["detail"] = json!({"path": "trip.json", "os_error": 13});
            }
            Case::named(kind.name(), request)
        })
        .collect()
}

#[cfg(any(test, feature = "test-ops"))]
fn no_cases() -> Vec<Case> {
    Vec::new()
}

#[cfg(any(test, feature = "test-ops"))]
fn panic_op(_: Value) -> Result<Value, FfiError> {
    panic!("core.panic requested")
}

// {"kind": "tick", "count": 3}: a Rust thread emits `count` events {"n": i} to the sink.
#[cfg(any(test, feature = "test-ops"))]
fn emit_test(body: Value) -> Result<Value, FfiError> {
    let kind = body
        .get("kind")
        .and_then(Value::as_str)
        .unwrap_or("test")
        .to_owned();
    let count = body.get("count").and_then(Value::as_u64).unwrap_or(1);
    // From a Rust thread of its own, like real background work; joined so the answer follows the events.
    std::thread::spawn(move || {
        for n in 0..count {
            crate::events::emit(&kind, &json!({ "n": n }));
        }
    })
    .join()
    .map_err(|_| FfiError::new(ErrorKind::Panic, "emit thread panicked"))?;
    Ok(json!({ "emitted": count }))
}

#[cfg(test)]
mod tests {
    use super::super::call;
    use crate::envelope::{Answer, ErrorKind, decode};
    use crate::events::testing::{Fake, LOCK};
    use serde_json::json;
    use std::sync::Arc;

    fn answer(op: &str, request: &str) -> Answer {
        decode(&call(op, request.as_bytes())).unwrap()
    }

    #[test]
    fn version_and_echo() {
        let Answer::Ok(v) = answer("core.version", "") else {
            panic!()
        };
        assert_eq!(v["abi"], 1);
        assert_eq!(
            answer("core.echo", r#"{"a":[1,"x"]}"#),
            Answer::Ok(json!({"a": [1, "x"]}))
        );
        assert_eq!(answer("core.echo", ""), Answer::Ok(json!(null)));
        let Answer::Err(e) = answer("core.echo", "{") else {
            panic!()
        };
        assert_eq!(e.kind, ErrorKind::Parse);
    }

    #[test]
    fn data_dir_needs_init() {
        crate::state::init("/d", "/c", "app", 30).unwrap();
        let Answer::Ok(v) = answer("core.data_dir", "") else {
            panic!()
        };
        assert_eq!(v["data_dir"], "/d");
        assert_eq!(v["sdk"], 30);
    }

    #[test]
    fn fail_maps_every_kind() {
        for kind in ErrorKind::ALL {
            let body = json!({"kind": kind.name(), "message": "m", "detail": {"x": 1}});
            let Answer::Err(e) = answer("core.fail", &body.to_string()) else {
                panic!()
            };
            assert_eq!(e.kind, kind);
            assert_eq!(e.message, "m");
            assert_eq!(e.detail, Some(json!({"x": 1})));
        }
        let Answer::Err(e) = answer("core.fail", r#"{"kind":"weird"}"#) else {
            panic!()
        };
        assert_eq!(e.kind, ErrorKind::Invalid);
    }

    #[test]
    fn panic_op_is_contained_and_next_call_works() {
        let Answer::Err(e) = answer("core.panic", "") else {
            panic!()
        };
        assert_eq!(e.kind, ErrorKind::Panic);
        assert!(e.message.contains("core.panic"));
        assert!(matches!(answer("core.version", ""), Answer::Ok(_)));
    }

    #[test]
    fn emit_test_reaches_the_sink() {
        let _guard = LOCK.lock().unwrap_or_else(|e| e.into_inner());
        let fake = Arc::new(Fake::default());
        crate::events::set_sink(Some(fake.clone()));
        assert_eq!(
            answer("core.emit_test", r#"{"kind":"k","count":3}"#),
            Answer::Ok(json!({"emitted": 3}))
        );
        crate::events::set_sink(None);
        assert_eq!(fake.0.lock().unwrap().len(), 3);
    }
}
