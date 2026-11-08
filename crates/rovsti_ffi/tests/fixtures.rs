//! Contract fixtures (E2.7): one `<op>.{request,answer}.json` pair per case of every registered op
//! (`<op>.<label>.*` for extra cases) and one `headless.<kind>.{request,answer}.json` pair per headless
//! kind, in `crates/rovsti_ffi/fixtures`. `fixtures/INDEX` lists every stem, one per line.
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
use rovsti_ffi::{
    envelope::ErrorKind,
    handles,
    ops::{
        call,
        registry::{self, PLACEHOLDER_HANDLE},
    },
    state,
};

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
    let mut stems = BTreeSet::new();

    // Fixed, host-independent init so `core.data_dir` is stable.
    state::init(
        "/data/user/0/dev.rovsti.fixture/files",
        "/data/user/0/dev.rovsti.fixture/cache",
        "dev.rovsti.fixture",
        34,
    )
    .expect("init");

    for spec in registry::all() {
        for case in (spec.cases)() {
            let stem = case.stem(spec.name);
            let text = case.request.to_string();
            let (text, tmp) = match case.seed {
                Some(seed) => {
                    let tmp = std::env::temp_dir()
                        .join(format!("rovsti-fixtures-{stem}-{}", std::process::id()));
                    let _ = fs::remove_dir_all(&tmp);
                    copy_dir(&app_core_dirs().join(seed), &tmp).expect("seed dir");
                    let replaced = text.replace("$DIR", tmp.to_str().expect("utf-8 temp dir"));
                    (replaced, Some(tmp))
                }
                None => (text, None),
            };
            // A case that needs a handle opens it first; the live number stands in for the placeholder.
            let mut live: Vec<i64> = Vec::new();
            let text = match &case.setup {
                Some((setup_op, setup_req)) => {
                    let opened = call(setup_op, setup_req.to_string().as_bytes());
                    let opened: serde_json::Value = serde_json::from_slice(&opened).expect("json");
                    let handle = opened["ok"]["handle"].as_i64().unwrap_or_else(|| {
                        panic!("{stem}: setup {setup_op} gave no handle: {opened}")
                    });
                    live.push(handle);
                    text.replace(&PLACEHOLDER_HANDLE.to_string(), &handle.to_string())
                }
                None => text,
            };
            let mut answer = String::from_utf8(call(spec.name, text.as_bytes())).expect("utf-8");
            if let Ok(parsed) = serde_json::from_str::<serde_json::Value>(&answer)
                && let Some(handle) = parsed["ok"]["handle"].as_i64()
                && !live.contains(&handle)
            {
                live.push(handle);
            }
            for handle in live {
                answer = answer.replace(&handle.to_string(), &PLACEHOLDER_HANDLE.to_string());
                handles::global().release(handle);
            }
            if let Some(tmp) = tmp {
                let _ = fs::remove_dir_all(tmp);
            }
            // The request file keeps `$DIR`; only the run above sees the temp dir.
            check(
                &format!("{stem}.request.json"),
                &case.request.to_string(),
                true,
                &mut problems,
            );
            check(
                &format!("{stem}.answer.json"),
                &answer,
                false,
                &mut problems,
            );
            expected.insert(format!("{stem}.request.json"));
            expected.insert(format!("{stem}.answer.json"));
            stems.insert(stem);
        }
    }

    for (app, kind) in table_kinds() {
        let request_name = format!("headless.{kind}.request.json");
        let answer_name = format!("headless.{kind}.answer.json");
        expected.insert(request_name.clone());
        expected.insert(answer_name.clone());
        stems.insert(format!("headless.{kind}"));
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

    // The index the Kotlin contract test reads instead of a hard-coded count.
    let index: String = stems.iter().map(|stem| format!("{stem}\n")).collect();
    check("INDEX", &index, false, &mut problems);
    expected.insert("INDEX".to_owned());

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
    let fail = registry::find("core.fail").expect("core.fail is registered");
    let labels: BTreeSet<&str> = (fail.cases)().iter().map(|c| c.label).collect();
    for kind in ErrorKind::ALL {
        assert!(labels.contains(kind.name()), "{}", kind.name());
    }
}

#[test]
fn every_registered_op_has_a_main_or_named_case() {
    for spec in registry::all() {
        let cases = (spec.cases)();
        let test_only = matches!(spec.name, "core.panic" | "core.emit_test");
        assert!(
            test_only || !cases.is_empty(),
            "{} has no fixture case",
            spec.name
        );
    }
}
