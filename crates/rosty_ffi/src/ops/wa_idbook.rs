//! `wa_idbook.*`: Phone's own WhatsApp identity table (`rosty_app_core::phone::wa_idbook`), a file
//! (`wa-identity.json`) in the data directory.
//!
//! Phone shows no WhatsApp messages and never opens the offline store of Messages (`wa_sync.*`); this table is all
//! it keeps to name a caller that arrives under a LID. A request may name `dir` (tests, fixtures), else
//! `core.init`'s data dir is used. Every op names the `instance_id` of the link: a file of another instance reads
//! empty. No op touches the network: Kotlin makes the `wa.identity_mappings` call and hands the page in.
//!
//! With `vault: true` (Phone since its WhatsApp vault, `plans/wa-vault.md` 13.5) the table lives only in the
//! DK-encrypted vault cache (`wa-vault-cache.bin`) of the unlocked session: every op answers `locked` while the vault
//! is, and the first read moves a plaintext `wa-identity.json` of an earlier build into the cache and deletes it.

use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use crate::state;
use rosty_app_core::phone::wa_idbook::{Book, CaughtUp, Pair};
use rosty_core::wa_identity::Source;
use rosty_wa_proto::{IdentityMapping, IdentityMappings};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::path::{Path, PathBuf};

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "wa_idbook.snapshot",
        SnapshotReq,
        SnapshotAns,
        snapshot,
        snapshot_cases
    ),
    op!(
        "wa_idbook.catch_up",
        CatchUpReq,
        CaughtUp,
        catch_up,
        catch_up_cases
    ),
    op!("wa_idbook.learn", LearnReq, LearnAns, learn, learn_cases),
    op!(
        "wa_idbook.account",
        AccountReq,
        AccountAns,
        account,
        account_cases
    ),
    op!("wa_idbook.wipe", WipeReq, Empty, wipe, wipe_cases),
];

const NOW: i64 = 1_790_000_000_000;
const INSTANCE: &str = "inst-1";

/// Serializes the users of `wa-identity.json`: each writer loads, changes and saves the whole file, and the
/// Kotlin side may call two at once (a call event during a catch-up).
fn book_lock() -> std::sync::MutexGuard<'static, ()> {
    static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
    LOCK.lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

fn data_dir(dir: &Option<String>) -> Result<PathBuf, FfiError> {
    match dir {
        Some(dir) => Ok(dir.into()),
        None => state::dirs()
            .map(|d| d.data_dir.clone())
            .ok_or_else(|| FfiError::invalid("init was not called")),
    }
}

#[derive(Serialize)]
struct Empty {}

/// The table of `instance`: from the file, or (`vault`) from the vault cache, moving a plaintext file in first.
fn load_book(dir: &Path, vault: bool, instance: &str) -> Result<Book, FfiError> {
    if !vault {
        return Ok(Book::load(dir, instance));
    }
    if let Some(value) = super::vault::with_cache(false, |cache| cache.id_book.clone())? {
        return Ok(Book::from_value(value, instance));
    }
    let book = Book::load(dir, instance);
    if dir.join(rosty_app_core::phone::wa_idbook::FILE).exists() {
        save_book(dir, true, &book)?;
        Book::wipe(dir)?;
    }
    Ok(book)
}

/// Writes the table back where [`load_book`] read it.
fn save_book(dir: &Path, vault: bool, book: &Book) -> Result<(), FfiError> {
    if vault {
        super::vault::with_cache(true, |cache| cache.id_book = Some(book.to_value()))
    } else {
        book.save(dir).map_err(FfiError::from)
    }
}

// ---- wa_idbook.snapshot

#[derive(Deserialize)]
struct SnapshotReq {
    #[serde(default)]
    dir: Option<String>,
    /// The table is in the vault cache, not in `wa-identity.json`.
    #[serde(default)]
    vault: bool,
    instance_id: String,
}

#[derive(Serialize)]
struct SnapshotAns {
    /// `since` of the next `wa.identity_mappings` call; left out before the first page.
    #[serde(skip_serializing_if = "Option::is_none")]
    since: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    own_pn: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    own_lid: Option<String>,
    /// Every LID the table can name with the current number of its person.
    pairs: Vec<Pair>,
}

fn snapshot(req: SnapshotReq) -> Result<SnapshotAns, FfiError> {
    let dir = data_dir(&req.dir)?;
    let _reading = book_lock();
    let book = load_book(&dir, req.vault, &req.instance_id)?;
    let (own_pn, own_lid) = book.own();
    Ok(SnapshotAns {
        since: book.since().map(str::to_owned),
        own_pn: own_pn.map(str::to_owned),
        own_lid: own_lid.map(str::to_owned),
        pairs: book.pairs(),
    })
}

fn snapshot_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR", "instance_id": INSTANCE})).seeded("wa_idbook"),
        // The file belongs to another instance: nothing of it is read.
        Case::named(
            "other_instance",
            json!({"dir": "$DIR", "instance_id": "inst-2"}),
        )
        .seeded("wa_idbook"),
        Case::named(
            "missing",
            json!({"dir": "$DIR/none", "instance_id": INSTANCE}),
        )
        .seeded("wa_idbook"),
        // The vault is locked: nothing is read, not even the plaintext file.
        Case::named(
            "vault_locked",
            json!({"dir": "$DIR", "instance_id": INSTANCE, "vault": true}),
        )
        .seeded("wa_idbook"),
        // The unlocked vault has no table yet; the seeded vault's instance has none on disk either.
        Case::named(
            "vault",
            json!({"dir": "$DIR", "instance_id": "inst1", "vault": true}),
        )
        .seeded("wa_vault")
        .after("vault.unlock", super::vault::seed_unlock_request()),
    ]
}

// ---- wa_idbook.catch_up

#[derive(Deserialize)]
struct CatchUpReq {
    #[serde(default)]
    dir: Option<String>,
    /// The table is in the vault cache, not in `wa-identity.json`.
    #[serde(default)]
    vault: bool,
    instance_id: String,
    /// One page of `wa.identity_mappings`, as it came.
    #[serde(default)]
    items: Vec<IdentityMapping>,
    #[serde(default)]
    next: Option<String>,
}

fn catch_up(req: CatchUpReq) -> Result<CaughtUp, FfiError> {
    let dir = data_dir(&req.dir)?;
    let _writing = book_lock();
    let mut book = load_book(&dir, req.vault, &req.instance_id)?;
    let done = book.catch_up(&IdentityMappings {
        items: req.items,
        next: req.next,
    });
    if done.changed {
        save_book(&dir, req.vault, &book)?;
    }
    Ok(done)
}

fn catch_up_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"dir": "$DIR", "instance_id": INSTANCE, "next": "c2", "items": [
                {"lid": "777000000000002@lid", "pn": "100000000002@s.whatsapp.net", "source": "peer_message",
                 "learned_ms": NOW},
            ]}),
        )
        .seeded("wa_idbook"),
        // The empty last page: the cursor stays and nothing is written.
        Case::named(
            "done",
            json!({"dir": "$DIR", "instance_id": INSTANCE, "items": []}),
        )
        .seeded("wa_idbook"),
        // A pair that is not a LID and a number is skipped, and a source a newer gateway names still reads.
        Case::named(
            "odd_pairs",
            json!({"dir": "$DIR", "instance_id": INSTANCE, "next": "c3", "items": [
                {"lid": "100000000003@s.whatsapp.net", "pn": "100000000004@s.whatsapp.net", "source": "usync",
                 "learned_ms": NOW},
                {"lid": "777000000000005@lid", "pn": "100000000005@s.whatsapp.net", "source": "something_new",
                 "learned_ms": NOW},
            ]}),
        )
        .seeded("wa_idbook"),
    ]
}

// ---- wa_idbook.learn

#[derive(Deserialize)]
struct LearnReq {
    #[serde(default)]
    dir: Option<String>,
    /// The table is in the vault cache, not in `wa-identity.json`.
    #[serde(default)]
    vault: bool,
    instance_id: String,
    now_ms: i64,
    /// Two ids of one person, in either order: an `identity_mapped` event, a call's `peer` and `peer_alt`.
    #[serde(default)]
    pairs: Vec<PairReq>,
    /// `identity_number_changed` events.
    #[serde(default)]
    number_changes: Vec<NumberChangeReq>,
}

#[derive(Deserialize)]
struct PairReq {
    id: String,
    alt: String,
    /// The gateway's source name; `call` when left out.
    #[serde(default)]
    source: Option<String>,
    /// When the gateway learned the pair; the request's `now_ms` when left out.
    #[serde(default)]
    learned_ms: Option<i64>,
}

#[derive(Deserialize)]
struct NumberChangeReq {
    old_pn: String,
    new_pn: String,
    #[serde(default)]
    old_lid: Option<String>,
    #[serde(default)]
    new_lid: Option<String>,
}

#[derive(Serialize)]
struct LearnAns {
    /// A row changed and the file was written: read `wa_idbook.snapshot` again.
    changed: bool,
}

fn learn(req: LearnReq) -> Result<LearnAns, FfiError> {
    let dir = data_dir(&req.dir)?;
    let _writing = book_lock();
    let mut book = load_book(&dir, req.vault, &req.instance_id)?;
    let mut changed = false;
    for pair in &req.pairs {
        let source = pair
            .source
            .as_deref()
            .map_or(Source::Call, Source::from_wire);
        changed |= book.learn_alt(
            &pair.id,
            &pair.alt,
            source,
            pair.learned_ms.unwrap_or(req.now_ms),
        );
    }
    for change in &req.number_changes {
        changed |= book.number_changed(
            &change.old_pn,
            &change.new_pn,
            change.old_lid.as_deref(),
            change.new_lid.as_deref(),
            req.now_ms,
        );
    }
    if changed {
        save_book(&dir, req.vault, &book)?;
    }
    Ok(LearnAns { changed })
}

fn learn_cases() -> Vec<Case> {
    vec![
        // A call whose peer is a LID with the number next to it.
        Case::new(
            json!({"dir": "$DIR", "instance_id": INSTANCE, "now_ms": NOW, "pairs": [
                {"id": "777000000000002:3@lid", "alt": "100000000002@s.whatsapp.net"},
            ]}),
        )
        .seeded("wa_idbook"),
        Case::named(
            "known",
            json!({"dir": "$DIR", "instance_id": INSTANCE, "now_ms": NOW, "pairs": [
                {"id": "100000000001@s.whatsapp.net", "alt": "777000000000001@lid", "source": "usync",
                 "learned_ms": 10},
            ]}),
        )
        .seeded("wa_idbook"),
        Case::named(
            "number_change",
            json!({"dir": "$DIR", "instance_id": INSTANCE, "now_ms": NOW, "number_changes": [
                {"old_pn": "100000000001@s.whatsapp.net", "new_pn": "100000000006@s.whatsapp.net",
                 "old_lid": "777000000000001@lid"},
            ]}),
        )
        .seeded("wa_idbook"),
        // Into the vault cache of the unlocked session.
        Case::named(
            "vault",
            json!({"dir": "$DIR", "instance_id": "inst1", "vault": true, "now_ms": NOW, "pairs": [
                {"id": "777000000000002:3@lid", "alt": "100000000002@s.whatsapp.net"},
            ]}),
        )
        .seeded("wa_vault")
        .after("vault.unlock", super::vault::seed_unlock_request()),
        // Two numbers, a group: nothing to learn.
        Case::named(
            "not_a_pair",
            json!({"dir": "$DIR", "instance_id": INSTANCE, "now_ms": NOW, "pairs": [
                {"id": "100000000002@s.whatsapp.net", "alt": "100000000003@s.whatsapp.net"},
                {"id": "777000000000004@lid", "alt": "120000000000000001@g.us"},
            ]}),
        )
        .seeded("wa_idbook"),
    ]
}

// ---- wa_idbook.account

#[derive(Deserialize)]
struct AccountReq {
    #[serde(default)]
    dir: Option<String>,
    /// The table is in the vault cache, not in `wa-identity.json`.
    #[serde(default)]
    vault: bool,
    instance_id: String,
    /// The account's own number (`wa.status`'s `own_jid`, the event's `new_pn`).
    #[serde(default)]
    own_pn: Option<String>,
    #[serde(default)]
    own_lid: Option<String>,
    #[serde(default)]
    account_epoch: Option<u32>,
    /// The gateway's `account_changed` event: another account, whatever the table held.
    #[serde(default)]
    changed: bool,
}

#[derive(Serialize)]
struct AccountAns {
    /// Another account than the stored one: the table and its cursor were emptied. Catch up from the start.
    reset: bool,
}

fn account(req: AccountReq) -> Result<AccountAns, FfiError> {
    let dir = data_dir(&req.dir)?;
    let _writing = book_lock();
    let mut book = load_book(&dir, req.vault, &req.instance_id)?;
    let before = book.clone();
    let reset = if req.changed {
        book.account_changed(req.own_pn.as_deref());
        true
    } else {
        book.note_account(
            req.own_pn.as_deref(),
            req.own_lid.as_deref(),
            req.account_epoch,
        )
    };
    if book != before {
        save_book(&dir, req.vault, &book)?;
    }
    Ok(AccountAns { reset })
}

fn account_cases() -> Vec<Case> {
    vec![
        // The same account: the own LID is new, nothing is emptied.
        Case::new(json!({"dir": "$DIR", "instance_id": INSTANCE,
            "own_pn": "100000000009:2@s.whatsapp.net", "own_lid": "777000000000009@lid", "account_epoch": 1}))
        .seeded("wa_idbook"),
        Case::named(
            "other_number",
            json!({"dir": "$DIR", "instance_id": INSTANCE, "own_pn": "100000000008@s.whatsapp.net"}),
        )
        .seeded("wa_idbook"),
        Case::named(
            "other_epoch",
            json!({"dir": "$DIR", "instance_id": INSTANCE, "account_epoch": 2}),
        )
        .seeded("wa_idbook"),
        Case::named(
            "event",
            json!({"dir": "$DIR", "instance_id": INSTANCE, "own_pn": "100000000008@s.whatsapp.net",
                "changed": true}),
        )
        .seeded("wa_idbook"),
        // A status that names nothing changes nothing.
        Case::named(
            "nothing_known",
            json!({"dir": "$DIR", "instance_id": INSTANCE}),
        )
        .seeded("wa_idbook"),
    ]
}

// ---- wa_idbook.wipe

#[derive(Deserialize)]
struct WipeReq {
    #[serde(default)]
    dir: Option<String>,
    /// The table is in the vault cache, not in `wa-identity.json`.
    #[serde(default)]
    vault: bool,
}

/// Removes the table (the link was removed). A missing file is fine.
fn wipe(req: WipeReq) -> Result<Empty, FfiError> {
    let dir = data_dir(&req.dir)?;
    let _writing = book_lock();
    Book::wipe(&dir)?;
    if req.vault {
        // A locked vault keeps its cache until the reset that follows an unlink deletes it.
        if super::vault::unlocked_instance().is_some() {
            super::vault::with_cache(true, |cache| cache.id_book = None)?;
        }
    }
    Ok(Empty {})
}

fn wipe_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dir": "$DIR"})).seeded("wa_idbook"),
        Case::named("missing", json!({"dir": "$DIR/none"})).seeded("wa_idbook"),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    const LID: &str = "777000000000002@lid";
    const PN: &str = "100000000002@s.whatsapp.net";

    /// A fresh directory under the system temp dir, removed when dropped.
    struct TempDir(PathBuf);

    impl TempDir {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir()
                .join(format!("rosty-ffi-wa-idbook-{name}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).expect("temp dir");
            Self(dir)
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn dir_of(tmp: &TempDir) -> Option<String> {
        Some(tmp.0.to_str().expect("utf-8 temp dir").to_owned())
    }

    fn read(tmp: &TempDir) -> SnapshotAns {
        snapshot(SnapshotReq {
            dir: dir_of(tmp),
            instance_id: INSTANCE.into(),
            vault: false,
        })
        .expect("snapshot")
    }

    fn learn_call(tmp: &TempDir, id: &str, alt: &str) -> bool {
        learn(LearnReq {
            dir: dir_of(tmp),
            instance_id: INSTANCE.into(),
            now_ms: NOW,
            pairs: vec![PairReq {
                id: id.into(),
                alt: alt.into(),
                source: None,
                learned_ms: None,
            }],
            number_changes: Vec::new(),
            vault: false,
        })
        .expect("learn")
        .changed
    }

    #[test]
    fn a_call_pair_is_kept_and_read_back() {
        let tmp = TempDir::new("pair");
        assert!(read(&tmp).pairs.is_empty());
        assert!(learn_call(&tmp, LID, PN));
        assert!(!learn_call(&tmp, PN, LID));
        assert_eq!(
            read(&tmp).pairs,
            [Pair {
                lid: LID.into(),
                pn: PN.into()
            }]
        );
    }

    #[test]
    fn the_catch_up_resumes_and_an_account_change_starts_it_over() {
        let tmp = TempDir::new("resume");
        let done = catch_up(CatchUpReq {
            dir: dir_of(&tmp),
            instance_id: INSTANCE.into(),
            items: vec![IdentityMapping {
                lid: LID.into(),
                pn: PN.into(),
                source: "usync".into(),
                learned_ms: 1,
            }],
            next: Some("c1".into()),
            vault: false,
        })
        .expect("catch up");
        assert_eq!((done.since.as_deref(), done.more), (Some("c1"), true));
        assert_eq!(read(&tmp).since.as_deref(), Some("c1"));

        let answer = account(AccountReq {
            dir: dir_of(&tmp),
            instance_id: INSTANCE.into(),
            own_pn: Some("100000000008@s.whatsapp.net".into()),
            own_lid: None,
            account_epoch: None,
            changed: true,
            vault: false,
        })
        .expect("account");
        assert!(answer.reset);
        let after = read(&tmp);
        assert_eq!(after.since, None);
        assert!(after.pairs.is_empty());
    }

    #[test]
    fn wipe_removes_the_table() {
        let tmp = TempDir::new("wipe");
        learn_call(&tmp, LID, PN);
        wipe(WipeReq {
            dir: dir_of(&tmp),
            vault: false,
        })
        .expect("wipe");
        assert!(read(&tmp).pairs.is_empty());
    }
}
