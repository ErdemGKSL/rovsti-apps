//! The panic guard: no panic crosses the JNI boundary.

use crate::envelope::{Answer, ErrorKind, FfiError, encode};
use serde_json::Value;
use std::any::Any;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Once;

/// Runs `f` and encodes its result; a panic becomes `kind = panic` with the payload as message.
pub fn guarded<F>(f: F) -> Vec<u8>
where
    F: FnOnce() -> Result<Value, FfiError>,
{
    let answer = match catch_unwind(AssertUnwindSafe(f)) {
        Ok(result) => Answer::from(result),
        Err(payload) => Answer::Err(FfiError::new(ErrorKind::Panic, payload_message(&*payload))),
    };
    encode(&answer)
}

/// Runs `f`, answering `fallback` when it panics (for exports that do not return an envelope).
pub fn or_fallback<R>(fallback: R, f: impl FnOnce() -> R) -> R {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(fallback)
}

fn payload_message(payload: &(dyn Any + Send)) -> String {
    if let Some(text) = payload.downcast_ref::<&'static str>() {
        (*text).to_owned()
    } else if let Some(text) = payload.downcast_ref::<String>() {
        text.clone()
    } else {
        "panic without a message".to_owned()
    }
}

/// Installs a hook that logs every panic through `log`. Idempotent.
pub fn install_panic_hook() {
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        let previous = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            log::error!("rust panic: {info}");
            previous(info);
        }));
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::envelope::decode;
    use serde_json::json;

    #[test]
    fn panic_becomes_panic_kind_and_the_next_call_works() {
        let bytes = guarded(|| panic!("boom {}", 7));
        match decode(&bytes).unwrap() {
            Answer::Err(error) => {
                assert_eq!(error.kind, ErrorKind::Panic);
                assert_eq!(error.message, "boom 7");
            }
            other => panic!("expected an error, got {other:?}"),
        }
        let bytes = guarded(|| Ok(json!("fine")));
        assert_eq!(decode(&bytes).unwrap(), Answer::Ok(json!("fine")));
    }

    #[test]
    fn static_str_payloads_and_errors_pass_through() {
        let bytes = guarded(|| std::panic::panic_any("plain"));
        assert!(matches!(decode(&bytes).unwrap(), Answer::Err(e) if e.message == "plain"));
        let bytes = guarded(|| Err(FfiError::new(ErrorKind::Auth, "no")));
        assert!(matches!(decode(&bytes).unwrap(), Answer::Err(e) if e.kind == ErrorKind::Auth));
    }

    #[test]
    fn fallback_is_used_on_panic() {
        assert_eq!(or_fallback(5, || panic!("x")), 5);
        assert_eq!(or_fallback(5, || 9), 9);
    }
}
