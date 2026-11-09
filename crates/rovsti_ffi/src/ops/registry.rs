//! The op registry: every op is one [`OpSpec`] in its domain's `OPS` table.
//!
//! Dispatch, the contract-fixture test and the inventory test all read these tables, so an op without
//! fixture cases, or a fixture without an op, fails `cargo test -p rovsti_ffi`.

use crate::envelope::FfiError;
use serde::{Serialize, de::DeserializeOwned};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::OnceLock;

/// One fixture case of an op: a request and, optionally, a seeded data directory.
#[derive(Debug, Clone, PartialEq)]
pub struct Case {
    /// `""` for the main case (`<op>.request.json`), else the suffix of `<op>.<label>.request.json`.
    pub label: &'static str,
    pub request: Value,
    /// A directory under `rovsti_app_core/tests/fixtures/dirs` copied to a temp dir; `$DIR` in the request text
    /// stands for it.
    pub seed: Option<&'static str>,
    /// Ops to run first, in order; each answer holds a `handle`. The next setup request (and finally the case
    /// request) names the latest handle as [`PLACEHOLDER_HANDLE`], and the fixture test swaps in the live one.
    pub setup: Vec<(&'static str, Value)>,
}

impl Case {
    /// The main case of an op.
    pub fn new(request: Value) -> Self {
        Self {
            label: "",
            request,
            seed: None,
            setup: Vec::new(),
        }
    }

    /// A named extra case (an error case, an edge).
    pub fn named(label: &'static str, request: Value) -> Self {
        Self {
            label,
            request,
            seed: None,
            setup: Vec::new(),
        }
    }

    pub fn seeded(mut self, seed: &'static str) -> Self {
        self.seed = Some(seed);
        self
    }

    /// Runs `op` with `request` first; its answer's `handle` replaces [`PLACEHOLDER_HANDLE`] in the next request.
    /// Several calls chain: each setup sees the handle of the one before.
    pub fn after(mut self, op: &'static str, request: Value) -> Self {
        self.setup.push((op, request));
        self
    }

    /// The fixture stem of this case for `op`.
    pub fn stem(&self, op: &str) -> String {
        if self.label.is_empty() {
            op.to_owned()
        } else {
            format!("{op}.{}", self.label)
        }
    }
}

/// The handle value fixtures carry. Real handles depend on test order; the fixture test swaps this number for
/// the live handle before running a case and back in the answer, so the files stay stable.
pub const PLACEHOLDER_HANDLE: i64 = 7_777_777_777;

/// A registered op: its wire name, its handler on raw request bytes and its fixture cases.
pub struct OpSpec {
    pub name: &'static str,
    pub run: fn(&[u8]) -> Result<Value, FfiError>,
    pub cases: fn() -> Vec<Case>,
}

/// Decodes the request (an empty body is `null`), runs `f`, encodes the answer.
pub fn run_typed<Req, Ans>(
    request: &[u8],
    f: fn(Req) -> Result<Ans, FfiError>,
) -> Result<Value, FfiError>
where
    Req: DeserializeOwned,
    Ans: Serialize,
{
    let body: &[u8] = if request.is_empty() { b"null" } else { request };
    let req: Req = serde_json::from_slice(body)?;
    Ok(serde_json::to_value(f(req)?)?)
}

/// Builds an [`OpSpec`] from a typed handler: `op!("format.render", Req, Ans, render, render_cases)`.
#[macro_export]
macro_rules! op {
    ($name:literal, $req:ty, $ans:ty, $handler:path, $cases:path) => {
        $crate::ops::registry::OpSpec {
            name: $name,
            run: |request| $crate::ops::registry::run_typed::<$req, $ans>(request, $handler),
            cases: $cases,
        }
    };
}

/// Every registered op, in table order.
pub fn all() -> impl Iterator<Item = &'static OpSpec> {
    super::core::OPS
        .iter()
        .chain(super::shared::OPS.iter())
        .chain(super::search::OPS.iter())
        .chain(super::phone::TABLES.iter().flat_map(|table| table.iter()))
        .chain(
            super::contacts::TABLES
                .iter()
                .flat_map(|table| table.iter()),
        )
        .chain(
            super::messages::TABLES
                .iter()
                .flat_map(|table| table.iter()),
        )
        .chain(super::chat::TABLES.iter().flat_map(|table| table.iter()))
        .chain(super::stops::TABLES.iter().flat_map(|table| table.iter()))
        .chain(super::qr::OPS.iter())
        .chain(super::tiles::OPS.iter())
        .chain(super::wa::TABLES.iter().flat_map(|table| table.iter()))
}

/// The op with this wire name.
pub fn find(name: &str) -> Option<&'static OpSpec> {
    static INDEX: OnceLock<HashMap<&'static str, &'static OpSpec>> = OnceLock::new();
    INDEX
        .get_or_init(|| all().map(|spec| (spec.name, spec)).collect())
        .get(name)
        .copied()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn op_names_are_unique_and_well_formed() {
        let mut seen = HashSet::new();
        for spec in all() {
            assert!(seen.insert(spec.name), "duplicate op {}", spec.name);
            let (domain, verb) = spec.name.split_once('.').expect("domain.verb");
            assert!(!domain.is_empty() && !verb.is_empty(), "{}", spec.name);
        }
    }

    #[test]
    fn find_reaches_every_op() {
        for spec in all() {
            assert_eq!(find(spec.name).map(|s| s.name), Some(spec.name));
        }
        assert!(find("nope.nope").is_none());
    }

    #[test]
    fn case_labels_are_unique_per_op() {
        for spec in all() {
            let mut labels = HashSet::new();
            for case in (spec.cases)() {
                assert!(
                    labels.insert(case.label),
                    "{}: label {:?}",
                    spec.name,
                    case.label
                );
            }
        }
    }
}
