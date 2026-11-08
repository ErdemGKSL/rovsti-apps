//! Contract fixtures (E2.7): one `<op>.{request,answer}.json` pair per R1 op and one
//! `headless.<kind>.{request,answer}.json` pair per headless kind, in `crates/rovsti_ffi/fixtures`.
//!
//! The Kotlin side decodes every file into its model (`ContractFixturesTest`), so an op or a
//! kind without a Kotlin model cannot be added unnoticed. Without `UPDATE_FIXTURES=1` this test
//! fails on any difference; with it, it rewrites the files. Answers of `core.*` ops are the full
//! envelope bytes; headless answers are the handler's string, byte for byte (the golden
//! answers of `rovsti_app_core`, which reads the same files).

use std::{
    collections::BTreeSet,
    fs, io,
    path::{Path, PathBuf},
};

use rovsti_app_core::headless::{Handler, handle_any};
use rovsti_ffi::{envelope::ErrorKind, ops::call, state};

fn fixtures_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures")
}

fn app_core_dirs() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../rovsti_app_core/tests/fixtures/dirs")
}

fn updating() -> bool {
    std::env::var("UPDATE_FIXTURES").is_ok_and(|v| v == "1")
}

/// Compares `actual` with the file, or writes it when updating. Requests are compared without
/// the trailing newline the files carry.
fn check(name: &str, actual: &str, newline: bool, problems: &mut Vec<String>) {
    let path = fixtures_dir().join(name);
    let wanted = if newline {
        format!("{actual}\n")
    } else {
        actual.to_owned()
    };
    if updating() {
        fs::write(&path, wanted).unwrap_or_else(|e| panic!("write {name}: {e}"));
        return;
    }
    match fs::read_to_string(&path) {
        Ok(found) if found.trim_end() == actual.trim_end() => {}
        Ok(found) => problems.push(format!("{name} differs\n  file: {found}\n  now:  {actual}")),
        Err(e) => problems.push(format!("{name} unreadable: {e}")),
    }
}

/// The R1 `core.*` ops and their requests.
fn core_cases() -> Vec<(String, String)> {
    let mut cases = vec![
        ("core.version".to_owned(), "null".to_owned()),
        (
            "core.echo".to_owned(),
            r#"{"text":"héllo 🌍","numbers":[1,2,3],"nested":{"flag":true,"note":null}}"#
                .to_owned(),
        ),
        ("core.data_dir".to_owned(), "null".to_owned()),
    ];
    for kind in ErrorKind::ALL {
        let detail = if kind == ErrorKind::Io {
            r#","detail":{"path":"trip.json","os_error":13}"#
        } else {
            ""
        };
        cases.push((
            format!("core.fail.{}", kind.name()),
            format!(
                r#"{{"kind":"{}","message":"requested {} failure"{detail}}}"#,
                kind.name(),
                kind.name()
            ),
        ));
    }
    cases
}

/// The op a `core.fail.<kind>` fixture stands for.
fn op_of(name: &str) -> &str {
    if name.starts_with("core.fail.") {
        "core.fail"
    } else {
        name
    }
}

fn table_kinds() -> Vec<(&'static str, &'static str)> {
    let tables: [(&str, &[(&str, Handler)]); 2] = [
        ("messages", rovsti_app_core::messages::headless::TABLE),
        ("stops", rovsti_app_core::stops::headless::TABLE),
    ];
    tables
        .into_iter()
        .flat_map(|(app, table)| table.iter().map(move |(kind, _)| (app, *kind)))
        .collect()
}

fn copy_dir(from: &Path, to: &Path) -> io::Result<()> {
    fs::create_dir_all(to)?;
    for entry in fs::read_dir(from)? {
        let entry = entry?;
        fs::copy(entry.path(), to.join(entry.file_name()))?;
    }
    Ok(())
}

#[test]
fn contract_fixtures_match() {
    fs::create_dir_all(fixtures_dir()).expect("fixtures dir");
    let mut problems = Vec::new();
    let mut expected = BTreeSet::new();

    // Fixed, host-independent init so `core.data_dir` is stable.
    state::init(
        "/data/user/0/dev.rovsti.fixture/files",
        "/data/user/0/dev.rovsti.fixture/cache",
        "dev.rovsti.fixture",
        34,
    )
    .expect("init");

    for (name, request) in core_cases() {
        let answer = String::from_utf8(call(op_of(&name), request.as_bytes())).expect("utf-8");
        check(
            &format!("{name}.request.json"),
            &request,
            true,
            &mut problems,
        );
        check(
            &format!("{name}.answer.json"),
            &answer,
            false,
            &mut problems,
        );
        expected.insert(format!("{name}.request.json"));
        expected.insert(format!("{name}.answer.json"));
    }

    for (app, kind) in table_kinds() {
        let request_name = format!("headless.{kind}.request.json");
        let answer_name = format!("headless.{kind}.answer.json");
        expected.insert(request_name.clone());
        expected.insert(answer_name.clone());
        // Headless requests are authored (they are inputs); `$DIR` stands for the data dir.
        let Ok(request) = fs::read_to_string(fixtures_dir().join(&request_name)) else {
            problems.push(format!("{request_name} is missing (write it by hand)"));
            continue;
        };
        let tmp =
            std::env::temp_dir().join(format!("rovsti-fixtures-{kind}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&tmp);
        copy_dir(&app_core_dirs().join(app), &tmp).expect("seed dir");
        let request = request
            .trim_end()
            .replace("$DIR", tmp.to_str().expect("utf-8 temp dir"));
        let answer = handle_any(kind, &request);
        let _ = fs::remove_dir_all(&tmp);
        check(&answer_name, &answer, false, &mut problems);
    }

    // No stray file: every fixture belongs to an op or a kind.
    for entry in fs::read_dir(fixtures_dir()).expect("read fixtures") {
        let name = entry
            .expect("entry")
            .file_name()
            .to_string_lossy()
            .into_owned();
        if !expected.contains(&name) {
            problems.push(format!("{name} belongs to no op or headless kind"));
        }
    }

    assert!(
        problems.is_empty(),
        "contract fixtures changed (UPDATE_FIXTURES=1 rewrites them):\n{}",
        problems.join("\n")
    );
}

#[test]
fn every_error_kind_has_a_fail_fixture() {
    let names: BTreeSet<String> = core_cases().into_iter().map(|(n, _)| n).collect();
    for kind in ErrorKind::ALL {
        assert!(names.contains(&format!("core.fail.{}", kind.name())));
    }
}
