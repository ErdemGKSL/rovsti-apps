//! The headless JNI entry: Kotlin asks Rust for a decision with no UI process.
//!
//! A `BroadcastReceiver` (SMS received, inline reply, FCM push) may run with no
//! activity. It loads the app's library (`Headless.ensureLoaded`) and calls
//! `RovstiBridge.nativeHeadless(kind, json)`, which lands in the app's
//! [`Handler`] through [`headless_entry!`]. `System.loadLibrary` only runs
//! `JNI_OnLoad`, which does not start GPUI.
//!
//! The handler is a plain blocking function; it must not touch GPUI. A panic
//! answers `""`, which Kotlin treats as "no decision" and falls back to a
//! plain notification.
//!
//! ```ignore
//! fn handle(kind: &str, json: &str) -> String {
//!     rovsti_android::headless::dispatch(kind, json, &[("sms", my_sms_handler)])
//! }
//! rovsti_android::headless_entry!(handle);
//! ```

use std::panic::{AssertUnwindSafe, catch_unwind};

/// A headless request handler: `(kind, json) -> json`.
pub type Handler = fn(&str, &str) -> String;

/// Runs the handler registered for `kind`; `""` for an unknown kind.
pub fn dispatch(kind: &str, json: &str, table: &[(&str, Handler)]) -> String {
    table
        .iter()
        .find(|(name, _)| *name == kind)
        .map(|(_, handler)| handler(kind, json))
        .unwrap_or_default()
}

/// Calls `handler`, answering `""` if it panics.
pub fn run_guarded(kind: &str, json: &str, handler: Handler) -> String {
    catch_unwind(AssertUnwindSafe(|| handler(kind, json))).unwrap_or_else(|_| {
        log::error!("headless handler panicked for kind {kind:?}");
        String::new()
    })
}

/// The JNI half of [`headless_entry!`]: reads both strings, runs the handler
/// and returns its answer (`null` if the strings cannot be read or written).
#[cfg(target_os = "android")]
pub fn run(
    env: &mut jni::JNIEnv<'_>,
    kind: jni::objects::JString<'_>,
    json: jni::objects::JString<'_>,
    handler: Handler,
) -> jni::sys::jstring {
    let mut read = |s: &jni::objects::JString<'_>| -> Option<String> {
        env.get_string(s).ok().map(String::from)
    };
    let (Some(kind), Some(json)) = (read(&kind), read(&json)) else {
        return std::ptr::null_mut();
    };
    let answer = run_guarded(&kind, &json, handler);
    env.new_string(answer)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Exports `Java_dev_rovsti_bridge_RovstiBridge_nativeHeadless` for an app,
/// calling `$handler` (a [`Handler`]). Expands to nothing off Android.
#[macro_export]
macro_rules! headless_entry {
    ($handler:path) => {
        #[cfg(target_os = "android")]
        #[unsafe(no_mangle)]
        pub extern "system" fn Java_dev_rovsti_bridge_RovstiBridge_nativeHeadless<'a>(
            mut env: $crate::jni::JNIEnv<'a>,
            _class: $crate::jni::objects::JClass<'a>,
            kind: $crate::jni::objects::JString<'a>,
            json: $crate::jni::objects::JString<'a>,
        ) -> $crate::jni::sys::jstring {
            $crate::headless::run(&mut env, kind, json, $handler)
        }
    };
}

#[cfg(test)]
mod tests {
    use super::*;

    fn echo(_: &str, json: &str) -> String {
        json.to_owned()
    }

    fn boom(_: &str, _: &str) -> String {
        panic!("boom")
    }

    #[test]
    fn dispatch_routes_by_kind() {
        let table: &[(&str, Handler)] = &[("echo", echo)];
        assert_eq!(dispatch("echo", "{\"a\":1}", table), "{\"a\":1}");
    }

    #[test]
    fn unknown_kind_is_empty() {
        let table: &[(&str, Handler)] = &[("echo", echo)];
        assert_eq!(dispatch("nope", "{}", table), "");
        assert_eq!(dispatch("echo", "{}", &[]), "");
    }

    #[test]
    fn a_panic_answers_empty() {
        assert_eq!(run_guarded("x", "{}", boom), "");
        assert_eq!(run_guarded("x", "7", echo), "7");
    }

    #[cfg(target_os = "android")]
    mod entry {
        fn handler(_: &str, json: &str) -> String {
            json.to_owned()
        }
        crate::headless_entry!(handler);
    }
}
