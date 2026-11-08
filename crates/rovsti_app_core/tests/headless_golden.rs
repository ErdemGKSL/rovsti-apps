//! Byte-identical proof for the headless handlers (E2.8): the answers in
//! `tests/fixtures/headless/<kind>.answer.json` were recorded with the code as it was in the
//! GPUI app crates, before it moved here. The request files use `$DIR` for the data dir, which
//! is a fresh copy of `tests/fixtures/dirs/<app>/` per request. Network kinds run against a
//! dir with no WhatsApp link, so they take their documented fallback and never connect.

use std::{fs, io, path::Path};

use rovsti_app_core::headless::handle_any;

fn root() -> &'static Path {
    Path::new(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures"))
}

fn copy_dir(from: &Path, to: &Path) -> io::Result<()> {
    fs::create_dir_all(to)?;
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        fs::copy(entry.path(), to.join(entry.file_name()))?;
    }
    Ok(())
}

fn answer_for(app: &str, kind: &str) -> (String, String) {
    let tmp = std::env::temp_dir().join(format!("rovsti-golden-{kind}-{}", std::process::id()));
    let _ = fs::remove_dir_all(&tmp);
    copy_dir(&root().join("dirs").join(app), &tmp).expect("seed dir");
    let request = fs::read_to_string(root().join(format!("headless/{kind}.request.json")))
        .expect("request")
        .trim_end()
        .replace("$DIR", tmp.to_str().expect("utf-8 temp dir"));
    let answer = handle_any(kind, &request);
    let _ = fs::remove_dir_all(&tmp);
    let golden =
        fs::read_to_string(root().join(format!("headless/{kind}.answer.json"))).expect("answer");
    (answer, golden)
}

macro_rules! golden {
    ($name:ident, $app:literal, $kind:literal) => {
        #[test]
        fn $name() {
            let (answer, golden) = answer_for($app, $kind);
            assert_eq!(answer, golden, "kind {}", $kind);
            assert!(!golden.is_empty());
        }
    };
}

golden!(sms_classify, "messages", "sms_classify");
golden!(mms_notification, "messages", "mms_notification");
golden!(mms_retrieved, "messages", "mms_retrieved");
golden!(mms_send_req, "messages", "mms_send_req");
golden!(wa_digest, "messages", "wa_digest");
golden!(wa_reply, "messages", "wa_reply");
golden!(msg_remind, "messages", "msg_remind");
golden!(trip_fix, "stops", "trip_fix");
golden!(trip_alarm, "stops", "trip_alarm");
golden!(trip_end, "stops", "trip_end");

#[test]
fn every_fixture_kind_is_covered_by_a_test() {
    let mut kinds: Vec<String> = fs::read_dir(root().join("headless"))
        .expect("dir")
        .filter_map(|e| {
            let name = e.ok()?.file_name().into_string().ok()?;
            name.strip_suffix(".answer.json").map(str::to_owned)
        })
        .collect();
    kinds.sort();
    assert_eq!(
        kinds,
        [
            "mms_notification",
            "mms_retrieved",
            "mms_send_req",
            "msg_remind",
            "sms_classify",
            "trip_alarm",
            "trip_end",
            "trip_fix",
            "wa_digest",
            "wa_reply"
        ]
    );
}

// ---- AD-MSG4 fallback: "no decision" is the empty string.

#[test]
fn an_unknown_kind_answers_empty() {
    assert_eq!(handle_any("no_such_kind", "{}"), "");
    assert_eq!(handle_any("", ""), "");
}

#[test]
fn malformed_json_never_panics_and_trip_kinds_answer_empty() {
    for kind in ["trip_fix", "trip_alarm", "trip_end"] {
        assert_eq!(handle_any(kind, "{not json"), "", "{kind}");
        assert_eq!(handle_any(kind, ""), "", "{kind}");
    }
    // Messages kinds answer their documented JSON fallback (ham, `ok:false`, `{}`), which
    // Kotlin also treats as "no decision".
    for kind in [
        "sms_classify",
        "mms_notification",
        "mms_retrieved",
        "mms_send_req",
        "wa_digest",
        "wa_reply",
        "msg_remind",
    ] {
        let answer = handle_any(kind, "{not json");
        serde_json::from_str::<serde_json::Value>(&answer)
            .unwrap_or_else(|e| panic!("{kind} answered {answer:?}: {e}"));
    }
}

#[test]
fn a_panicking_handler_answers_empty() {
    fn boom(_: &str, _: &str) -> String {
        panic!("boom")
    }
    assert_eq!(rovsti_app_core::headless::run_guarded("x", "{}", boom), "");
}
