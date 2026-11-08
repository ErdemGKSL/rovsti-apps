//! The headless entry shared by all apps: Kotlin asks Rust for a decision with no UI process.
//!
//! A handler is a plain blocking function `(kind, json) -> json`. An unknown kind, a bad
//! request or a panicking handler answers `""`, which Kotlin treats as "no decision" and
//! falls back to a plain notification (AD-MSG4).
//!
//! This is a small copy of `rovsti_android::headless` so this crate stays free of JNI; the
//! original is deleted together with the GPUI binaries.

use std::panic::{AssertUnwindSafe, catch_unwind};

/// A headless request handler: `(kind, json) -> json`.
pub type Handler = fn(&str, &str) -> String;

/// Runs the handler registered for `kind`; `""` for an unknown kind.
pub fn dispatch(kind: &str, json: &str, table: &[(&str, Handler)]) -> String {
    find(kind, table)
        .map(|handler| handler(kind, json))
        .unwrap_or_default()
}

fn find(kind: &str, table: &[(&str, Handler)]) -> Option<Handler> {
    table
        .iter()
        .find(|(name, _)| *name == kind)
        .map(|(_, handler)| *handler)
}

/// Calls `handler`, answering `""` if it panics.
pub fn run_guarded(kind: &str, json: &str, handler: Handler) -> String {
    catch_unwind(AssertUnwindSafe(|| handler(kind, json))).unwrap_or_else(|_| {
        log::error!("headless handler panicked for kind {kind:?}");
        String::new()
    })
}

/// Answers a request of any app: the Messages table, then the Stops table. `""` for a kind
/// neither knows or when the handler panics.
pub fn handle_any(kind: &str, json: &str) -> String {
    let handler = find(kind, crate::messages::headless::TABLE)
        .or_else(|| find(kind, crate::stops::headless::TABLE));
    handler.map_or_else(String::new, |handler| run_guarded(kind, json, handler))
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
    fn dispatch_routes_by_kind_and_answers_empty_for_unknown() {
        let table: &[(&str, Handler)] = &[("echo", echo)];
        assert_eq!(dispatch("echo", "{\"a\":1}", table), "{\"a\":1}");
        assert_eq!(dispatch("nope", "{}", table), "");
        assert_eq!(dispatch("echo", "{}", &[]), "");
    }

    #[test]
    fn a_panic_answers_empty() {
        assert_eq!(run_guarded("x", "{}", boom), "");
        assert_eq!(run_guarded("x", "7", echo), "7");
    }

    #[test]
    fn the_app_tables_share_no_kind() {
        let messages = crate::messages::headless::TABLE;
        let stops = crate::stops::headless::TABLE;
        for (kind, _) in messages {
            assert!(
                stops.iter().all(|(other, _)| other != kind),
                "kind {kind:?} is in both tables"
            );
        }
        let mut all: Vec<&str> = messages.iter().chain(stops).map(|(k, _)| *k).collect();
        let total = all.len();
        all.sort_unstable();
        all.dedup();
        assert_eq!(all.len(), total, "a table repeats a kind");
        assert_eq!(total, 10);
    }

    #[test]
    fn handle_any_reaches_both_apps_and_ignores_unknown_kinds() {
        assert_eq!(handle_any("unknown_kind", "{}"), "");
        assert_eq!(handle_any("", "{}"), "");
        assert!(handle_any("sms_classify", "{}").contains("\"spam\""));
        assert_eq!(handle_any("trip_end", "not json"), "");
    }
}
