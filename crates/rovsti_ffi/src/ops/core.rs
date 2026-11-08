//! `core.*`: ops about the bridge itself.

use super::parse;
use crate::envelope::{ErrorKind, FfiError};
use crate::state;
use serde_json::{Value, json};

pub(super) fn run(verb: &str, request: &[u8]) -> Result<Value, FfiError> {
    match verb {
        "version" => Ok(state::version()),
        "echo" => parse(request),
        "data_dir" => {
            let dirs = state::dirs().ok_or_else(|| FfiError::invalid("init was not called"))?;
            Ok(json!({
                "data_dir": dirs.data_dir,
                "cache_dir": dirs.cache_dir,
                "app_id": dirs.app_id,
                "sdk": dirs.sdk,
            }))
        }
        // {"kind": "io", "message": "...", "detail": {...}}: an error of the asked kind.
        "fail" => {
            let body = parse(request)?;
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
        #[cfg(any(test, feature = "test-ops"))]
        "panic" => panic!("core.panic requested"),
        // {"kind": "tick", "count": 3}: emits `count` events {"n": i} to the sink.
        #[cfg(any(test, feature = "test-ops"))]
        "emit_test" => {
            let body = parse(request)?;
            let kind = body.get("kind").and_then(Value::as_str).unwrap_or("test");
            let count = body.get("count").and_then(Value::as_u64).unwrap_or(1);
            for n in 0..count {
                crate::events::emit(kind, &json!({ "n": n }));
            }
            Ok(json!({ "emitted": count }))
        }
        _ => Err(FfiError::unsupported(format!("unknown op core.{verb}"))),
    }
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
