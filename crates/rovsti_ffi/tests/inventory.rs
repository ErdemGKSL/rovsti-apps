//! `OPS.md` is the inventory of every item the GPUI apps call and what becomes of it. This test keeps it honest:
//! the ops its `op` rows name are exactly the registered ops, every row has a known class, and no row of a
//! finished domain is still `todo`.

use rovsti_ffi::ops::registry;
use std::collections::BTreeSet;
use std::fs;
use std::path::Path;

/// Domains whose rows must all be classified. Grows with each step of E2 R2.
const DONE_DOMAINS: &[&str] = &[
    "bridge", "shared", "input", "phone", "search", "t9", "contacts", "qr", "messages", "stops",
];

/// Registered only in unit tests or with the `test-ops` feature; never in the fixture set.
const TEST_ONLY: &[&str] = &["core.panic", "core.emit_test"];

struct Row {
    item: String,
    domain: String,
    class: String,
    target: String,
}

fn rows() -> Vec<Row> {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("OPS.md");
    let text = fs::read_to_string(&path).expect("OPS.md");
    text.lines()
        .filter(|line| line.starts_with("| `"))
        .map(|line| {
            let cells: Vec<&str> = line.trim_matches('|').split('|').map(str::trim).collect();
            assert_eq!(cells.len(), 5, "row needs 5 cells: {line}");
            Row {
                item: cells[0].trim_matches('`').to_owned(),
                domain: cells[2].to_owned(),
                class: cells[3].to_owned(),
                target: cells[4].to_owned(),
            }
        })
        .collect()
}

#[test]
fn every_row_has_a_known_class_and_target() {
    for row in rows() {
        assert!(
            matches!(row.class.as_str(), "op" | "kotlin" | "none" | "todo"),
            "{}: unknown class {:?}",
            row.item,
            row.class
        );
        if row.class != "todo" {
            assert!(
                row.target != "-" && !row.target.is_empty(),
                "{}: class {} needs a target",
                row.item,
                row.class
            );
        }
    }
}

#[test]
fn op_rows_are_exactly_the_registered_ops() {
    let named: BTreeSet<String> = rows()
        .iter()
        .filter(|row| row.class == "op")
        .flat_map(|row| {
            row.target
                .split(',')
                .map(|n| n.trim().to_owned())
                .collect::<Vec<_>>()
        })
        .filter(|name| !TEST_ONLY.contains(&name.as_str()))
        .collect();
    let registered: BTreeSet<String> = registry::all()
        .map(|spec| spec.name.to_owned())
        .filter(|name| !TEST_ONLY.contains(&name.as_str()))
        .collect();
    let missing_row: Vec<_> = registered.difference(&named).collect();
    let missing_op: Vec<_> = named.difference(&registered).collect();
    assert!(
        missing_row.is_empty() && missing_op.is_empty(),
        "registered ops without an OPS.md row: {missing_row:?}; OPS.md ops that are not registered: {missing_op:?}"
    );
}

#[test]
fn finished_domains_have_no_todo() {
    let todo: Vec<String> = rows()
        .into_iter()
        .filter(|row| row.class == "todo" && DONE_DOMAINS.contains(&row.domain.as_str()))
        .map(|row| row.item)
        .collect();
    assert!(todo.is_empty(), "todo rows in finished domains: {todo:?}");
}
