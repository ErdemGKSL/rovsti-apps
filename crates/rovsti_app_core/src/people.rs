//! The people cache (X.1): what the apps know about the people they meet on WhatsApp, keyed by JID and kept
//! across runs so a contact without a photo can still show its WhatsApp picture, offline.
//!
//! Platform-free: the data directory is a `&Path` argument. The store is `people.json`; the picture files live
//! elsewhere (the caller names them) and are planned with the rules of `wa_link::avatars` (file names, oldest
//! first over a byte budget), not a second LRU.
//!
//! * A person is one JID. A phone JID (`905321234567@s.whatsapp.net`) carries the E.164 number; a LID
//!   (`123456@lid`) hides it. When the gateway tells us which number a LID belongs to, [`Book::upsert`] joins
//!   the LID record into the phone record, so a lookup by either finds one person.
//! * A picture-id change marks the picture stale (the old file keeps showing until the new one arrives);
//!   "no picture any more" drops the file.

use std::{
    fs, io,
    path::{Path, PathBuf},
    time::{Duration, UNIX_EPOCH},
};

use serde::{Deserialize, Serialize};

use crate::wa_link::avatars::{self, DiskFile, NONE_FOR_MS};

/// File name inside the app data directory.
pub const FILE: &str = "people.json";
pub const SCHEMA_VERSION: u32 = 1;
/// A file bigger than this is not read back (a damaged store must not eat memory).
pub const MAX_FILE_BYTES: usize = 8 * 1024 * 1024;
/// More people than this are not kept; the ones used longest ago go first.
pub const MAX_PEOPLE: usize = 5_000;
/// A picture is looked at again this long after it was last fetched.
pub const REVALIDATE_MS: i64 = 7 * 24 * 3_600_000;

const PHONE_SUFFIX: &str = "@s.whatsapp.net";
const LID_SUFFIX: &str = "@lid";

/// One person.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Person {
    /// The canonical key: the phone JID when the number is known, else the LID JID.
    pub jid: String,
    /// E.164 numbers (`+905321234567`), without repeats.
    #[serde(default)]
    pub numbers: Vec<String>,
    /// The LID user part (`123456`), when known.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lid: Option<String>,
    /// The name the person set for themselves.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub push_name: Option<String>,
    /// A business's verified name.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub verified_name: Option<String>,
    #[serde(default)]
    pub on_whatsapp: bool,
    /// The gateway's id of the current picture; `None`: none, or not known yet (see `fetched_ms`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub picture_id: Option<String>,
    /// The file name the picture is stored under (`avatars::file_name`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub picture_file: Option<String>,
    /// The picture id changed and the file is the old one (or missing).
    #[serde(default)]
    pub picture_stale: bool,
    /// When the picture was last asked for (0: never).
    #[serde(default)]
    pub fetched_ms: i64,
    #[serde(default)]
    pub updated_ms: i64,
}

impl Person {
    /// The provider's view of this person.
    pub fn row(&self) -> Row {
        Row {
            jid: self.jid.clone(),
            numbers: self.numbers.clone(),
            lid: self.lid.clone(),
            name: self.name().map(str::to_owned),
            on_whatsapp: self.on_whatsapp,
            picture_id: self.picture_id.clone(),
            picture_file: self.picture_file.clone(),
            picture_stale: self.picture_stale,
        }
    }

    /// The best name the cache has: verified, else push name.
    pub fn name(&self) -> Option<&str> {
        self.verified_name.as_deref().or(self.push_name.as_deref())
    }
}

/// What a caller knows about one person; every field but `jid` is optional and only what is present changes.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Update {
    pub jid: String,
    #[serde(default)]
    pub number: Option<String>,
    #[serde(default)]
    pub lid: Option<String>,
    #[serde(default)]
    pub push_name: Option<String>,
    #[serde(default)]
    pub verified_name: Option<String>,
    #[serde(default)]
    pub on_whatsapp: Option<bool>,
    /// The picture id the gateway announced.
    #[serde(default)]
    pub picture_id: Option<String>,
    /// The gateway says there is no picture (or it is hidden).
    #[serde(default)]
    pub picture_removed: bool,
    /// A picture file was just stored for `picture_id`.
    #[serde(default)]
    pub picture_file: Option<String>,
}

/// What an upsert changed outside the store.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct Upserted {
    /// People added or changed.
    pub changed: usize,
    /// JIDs whose picture is now stale.
    pub stale: Vec<String>,
    /// Picture files that no longer belong to anybody.
    pub drop_files: Vec<String>,
}

/// What a refresh pass should do.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct Plan {
    /// Pictures to fetch, with the id to ask for when known.
    pub refresh: Vec<Refresh>,
    /// Files to delete: orphans of a changed picture, then the oldest over the byte budget.
    pub remove: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Refresh {
    pub jid: String,
    pub picture_id: Option<String>,
}

/// A file on disk, for [`Book::plan`].
#[derive(Clone, Debug, PartialEq, Eq, Deserialize)]
pub struct FileInfo {
    pub path: String,
    pub bytes: u64,
    pub modified_ms: u64,
}

/// A person as the provider shows it: no times, one name.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Row {
    pub jid: String,
    pub numbers: Vec<String>,
    pub lid: Option<String>,
    pub name: Option<String>,
    pub on_whatsapp: bool,
    pub picture_id: Option<String>,
    /// Only a file that is current: a stale picture still shows (the old one), see `picture_stale`.
    pub picture_file: Option<String>,
    pub picture_stale: bool,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Book {
    pub schema_version: u32,
    #[serde(default)]
    pub people: Vec<Person>,
}

impl Default for Book {
    fn default() -> Self {
        Self {
            schema_version: SCHEMA_VERSION,
            people: Vec::new(),
        }
    }
}

/// `+905321234567` from any spelling with digits; `None` without at least 5 digits.
pub fn e164(raw: &str) -> Option<String> {
    let digits: String = raw.chars().filter(char::is_ascii_digit).collect();
    (digits.len() >= 5).then(|| format!("+{digits}"))
}

/// The part of a JID before `@` (and before a device suffix `:12`).
fn user_part(jid: &str) -> &str {
    let user = jid.split('@').next().unwrap_or(jid);
    user.split(':').next().unwrap_or(user)
}

/// The LID user part of `jid`, if it is a LID JID.
fn lid_of(jid: &str) -> Option<&str> {
    jid.ends_with(LID_SUFFIX).then(|| user_part(jid))
}

fn phone_jid(number: &str) -> Option<String> {
    let digits = number.trim_start_matches('+');
    (!digits.is_empty()).then(|| format!("{digits}{PHONE_SUFFIX}"))
}

fn non_empty(text: &str) -> Option<String> {
    let text = text.trim();
    (!text.is_empty()).then(|| text.to_owned())
}

impl Book {
    /// The person with this E.164 number, this JID (phone or LID) or this bare LID.
    pub fn lookup(&self, key: &str) -> Option<&Person> {
        let key = key.trim();
        if key.is_empty() {
            return None;
        }
        if key.contains('@') {
            let user = user_part(key);
            return self.people.iter().find(|p| {
                p.jid == key
                    || (key.ends_with(LID_SUFFIX) && p.lid.as_deref() == Some(user))
                    || (key.ends_with(PHONE_SUFFIX)
                        && p.numbers.iter().any(|n| n.trim_start_matches('+') == user))
            });
        }
        let number = e164(key);
        self.people
            .iter()
            .find(|p| number.as_ref().is_some_and(|n| p.numbers.contains(n)))
            .or_else(|| self.people.iter().find(|p| p.lid.as_deref() == Some(key)))
    }

    /// Applies what is known about people. Joins a LID record into the phone record once both are known.
    pub fn upsert(&mut self, updates: &[Update], now_ms: i64) -> Upserted {
        let mut out = Upserted::default();
        for update in updates {
            self.apply(update, now_ms, &mut out);
        }
        if self.people.len() > MAX_PEOPLE {
            self.people.sort_by_key(|p| std::cmp::Reverse(p.updated_ms));
            for gone in self.people.drain(MAX_PEOPLE..) {
                out.drop_files.extend(gone.picture_file);
            }
        }
        out.drop_files.sort();
        out.drop_files.dedup();
        out
    }

    fn apply(&mut self, update: &Update, now_ms: i64, out: &mut Upserted) {
        let number = update.number.as_deref().and_then(e164);
        let lid = update
            .lid
            .as_deref()
            .map(|l| user_part(l).to_owned())
            .or_else(|| lid_of(&update.jid).map(str::to_owned))
            .filter(|l| !l.is_empty());
        // The key the person ends up under: phone JID when a number is known.
        let own_number = if update.jid.ends_with(PHONE_SUFFIX) {
            e164(user_part(&update.jid))
        } else {
            None
        };
        let number = number.or(own_number);
        let key = match (&number, update.jid.contains('@')) {
            (Some(n), _) => phone_jid(n).unwrap_or_else(|| update.jid.clone()),
            (None, true) => update.jid.clone(),
            (None, false) => return,
        };

        // Every record this update may be about: by key, by LID, by number.
        let mut matches: Vec<usize> = Vec::new();
        for (i, p) in self.people.iter().enumerate() {
            let same = p.jid == key
                || p.jid == update.jid
                || lid.as_deref().is_some_and(|l| p.lid.as_deref() == Some(l))
                || number.as_ref().is_some_and(|n| p.numbers.contains(n));
            if same {
                matches.push(i);
            }
        }
        let mut person = match matches.first() {
            Some(&first) => self.people[first].clone(),
            None => Person {
                jid: key.clone(),
                ..Person::default()
            },
        };
        let before = person.clone();
        // Join the others into the first (their pictures: the first wins, the rest's files are dropped).
        for &i in matches.iter().skip(1).rev() {
            let other = self.people.remove(i);
            Self::merge(&mut person, other, out);
        }
        // The first index may have shifted only if a later one was removed (we removed larger indexes first).
        if let Some(&first) = matches.first() {
            self.people.remove(first);
        }
        person.jid = key;

        if let Some(n) = &number
            && !person.numbers.contains(n)
        {
            person.numbers.push(n.clone());
        }
        if let Some(l) = lid {
            person.lid = Some(l);
        }
        if let Some(name) = update.push_name.as_deref().and_then(non_empty) {
            person.push_name = Some(name);
        }
        if let Some(name) = update.verified_name.as_deref().and_then(non_empty) {
            person.verified_name = Some(name);
        }
        if let Some(on) = update.on_whatsapp {
            person.on_whatsapp = on;
        }
        // Anything we learn from WhatsApp about a JID means it is on WhatsApp.
        if update.picture_id.is_some() || update.picture_file.is_some() {
            person.on_whatsapp = true;
        }
        Self::picture(&mut person, update, now_ms, out);

        if person != before {
            person.updated_ms = now_ms;
            out.changed += 1;
        }
        if person.picture_stale {
            out.stale.push(person.jid.clone());
        }
        self.people.push(person);
    }

    fn merge(into: &mut Person, other: Person, out: &mut Upserted) {
        for n in other.numbers {
            if !into.numbers.contains(&n) {
                into.numbers.push(n);
            }
        }
        into.lid = into.lid.take().or(other.lid);
        into.push_name = into.push_name.take().or(other.push_name);
        into.verified_name = into.verified_name.take().or(other.verified_name);
        into.on_whatsapp |= other.on_whatsapp;
        into.fetched_ms = into.fetched_ms.max(other.fetched_ms);
        if into.picture_id.is_none() && into.picture_file.is_none() {
            into.picture_id = other.picture_id;
            into.picture_file = other.picture_file;
            into.picture_stale = other.picture_stale;
        } else {
            out.drop_files.extend(other.picture_file);
        }
    }

    fn picture(person: &mut Person, update: &Update, now_ms: i64, out: &mut Upserted) {
        if update.picture_removed {
            out.drop_files.extend(person.picture_file.take());
            person.picture_id = None;
            person.picture_stale = false;
            person.fetched_ms = now_ms;
            return;
        }
        let Some(id) = update.picture_id.as_deref().and_then(non_empty) else {
            if let Some(file) = update.picture_file.as_deref() {
                // A file without an id: keep the old one's name only when it is the same file.
                person.picture_file = Some(file.to_owned());
                person.picture_stale = false;
                person.fetched_ms = now_ms;
            }
            return;
        };
        let changed = person.picture_id.as_deref() != Some(id.as_str());
        person.picture_id = Some(id);
        match update.picture_file.as_deref() {
            Some(file) => {
                if let Some(old) = person.picture_file.replace(file.to_owned())
                    && old != file
                {
                    out.drop_files.push(old);
                }
                person.picture_stale = false;
                person.fetched_ms = now_ms;
            }
            None if changed => person.picture_stale = true,
            None => {}
        }
    }

    /// Which pictures to fetch now and which files to delete.
    ///
    /// A picture is fetched when it is stale, when its id is known but the file is missing from `files`, when it
    /// was never asked for, or when it was last fetched more than [`REVALIDATE_MS`] ago; "no picture" is
    /// believed for [`NONE_FOR_MS`]. Only people on WhatsApp are asked for. Files go when they hold an older
    /// picture of a known person, and then the oldest over `max_bytes`.
    pub fn plan(&self, now_ms: i64, files: &[FileInfo], max_bytes: u64) -> Plan {
        let name_of = |f: &FileInfo| {
            Path::new(&f.path)
                .file_name()
                .and_then(|n| n.to_str())
                .map(str::to_owned)
        };
        let mut refresh = Vec::new();
        for p in self.people.iter().filter(|p| p.on_whatsapp) {
            let has_file = p
                .picture_file
                .as_deref()
                .is_some_and(|name| files.iter().any(|f| name_of(f).as_deref() == Some(name)));
            let due = if p.picture_stale {
                true
            } else if p.picture_id.is_some() {
                !has_file || now_ms - p.fetched_ms > REVALIDATE_MS
            } else {
                p.fetched_ms == 0 || now_ms - p.fetched_ms > NONE_FOR_MS
            };
            if due {
                refresh.push(Refresh {
                    jid: p.jid.clone(),
                    picture_id: p.picture_id.clone(),
                });
            }
        }
        let mut remove = Vec::new();
        let mut kept = Vec::new();
        for f in files {
            let orphan = name_of(f).is_some_and(|name| {
                self.people.iter().any(|p| {
                    avatars::id_of_file(&p.jid, &name)
                        .is_some_and(|id| p.picture_id.as_deref() != Some(id.as_str()))
                })
            });
            if orphan {
                remove.push(f.path.clone());
            } else {
                kept.push(DiskFile {
                    path: PathBuf::from(&f.path),
                    bytes: f.bytes,
                    modified: UNIX_EPOCH + Duration::from_millis(f.modified_ms),
                });
            }
        }
        remove.extend(
            avatars::prune_plan(kept, max_bytes)
                .into_iter()
                .map(|p| p.to_string_lossy().into_owned()),
        );
        Plan { refresh, remove }
    }

    /// Compact rows for the provider, in a stable order (by name, then JID).
    pub fn snapshot(&self) -> Vec<Row> {
        let mut rows: Vec<Row> = self.people.iter().map(Person::row).collect();
        rows.sort_by(|a, b| {
            (
                a.name.as_deref().unwrap_or("\u{10FFFF}").to_lowercase(),
                &a.jid,
            )
                .cmp(&(
                    b.name.as_deref().unwrap_or("\u{10FFFF}").to_lowercase(),
                    &b.jid,
                ))
        });
        rows
    }
}

// ---- storage -----------------------------------------------------------------------------------

fn path(dir: &Path) -> PathBuf {
    dir.join(FILE)
}

/// The saved book; a missing, damaged or oversized file reads as an empty one (offline reads never fail).
pub fn load(dir: &Path) -> Book {
    let Ok(bytes) = fs::read(path(dir)) else {
        return Book::default();
    };
    if bytes.len() > MAX_FILE_BYTES {
        log::warn!("people cache is {} bytes; ignoring", bytes.len());
        return Book::default();
    }
    serde_json::from_slice(&bytes).unwrap_or_else(|error| {
        log::warn!("people cache unreadable ({error}); starting empty");
        Book::default()
    })
}

/// Writes the book through a temporary file, so a crash never leaves half a file.
pub fn save(dir: &Path, book: &Book) -> io::Result<()> {
    let mut copy = book.clone();
    copy.schema_version = SCHEMA_VERSION;
    let json = serde_json::to_vec(&copy).map_err(io::Error::other)?;
    if json.len() > MAX_FILE_BYTES {
        return Err(io::Error::other("the people cache would be too big"));
    }
    fs::create_dir_all(dir)?;
    let temp = dir.join(format!("{FILE}.tmp"));
    fs::write(&temp, json)?;
    fs::rename(&temp, path(dir))
}

#[cfg(test)]
mod tests {
    use super::*;

    const NOW: i64 = 1_790_000_000_000;
    const JID: &str = "905321234567@s.whatsapp.net";

    fn upd(jid: &str) -> Update {
        Update {
            jid: jid.into(),
            ..Update::default()
        }
    }

    fn pic(jid: &str, id: &str) -> Update {
        Update {
            picture_id: Some(id.into()),
            ..upd(jid)
        }
    }

    fn file(name: &str, bytes: u64, modified_ms: u64) -> FileInfo {
        FileInfo {
            path: format!("/cache/people/{name}"),
            bytes,
            modified_ms,
        }
    }

    #[test]
    fn insert_keeps_number_names_and_flag() {
        let mut book = Book::default();
        let out = book.upsert(
            &[Update {
                push_name: Some(" Ayse ".into()),
                on_whatsapp: Some(true),
                ..upd(JID)
            }],
            NOW,
        );
        assert_eq!(out.changed, 1);
        let p = book.lookup("+90 532 123 45 67").unwrap();
        assert_eq!(p.jid, JID);
        assert_eq!(p.numbers, ["+905321234567"]);
        assert_eq!(p.name(), Some("Ayse"));
        assert!(p.on_whatsapp);
        // the same data again changes nothing
        let again = book.upsert(
            &[Update {
                push_name: Some("Ayse".into()),
                ..upd(JID)
            }],
            NOW + 1,
        );
        assert_eq!(again.changed, 0);
        assert_eq!(book.people.len(), 1);
    }

    #[test]
    fn verified_name_beats_push_name() {
        let mut book = Book::default();
        book.upsert(
            &[Update {
                push_name: Some("a".into()),
                verified_name: Some("Shop".into()),
                ..upd(JID)
            }],
            NOW,
        );
        assert_eq!(book.lookup(JID).unwrap().name(), Some("Shop"));
    }

    #[test]
    fn picture_id_change_marks_stale_and_keeps_the_old_file() {
        let mut book = Book::default();
        let name = avatars::file_name(JID, "1");
        book.upsert(
            &[Update {
                picture_file: Some(name.clone()),
                ..pic(JID, "1")
            }],
            NOW,
        );
        assert!(!book.lookup(JID).unwrap().picture_stale);
        let out = book.upsert(&[pic(JID, "2")], NOW + 10);
        assert_eq!(out.stale, [JID]);
        let p = book.lookup(JID).unwrap();
        assert!(p.picture_stale);
        assert_eq!(
            p.picture_file.as_deref(),
            Some(name.as_str()),
            "old picture keeps showing"
        );
        // the same id again is not a change
        let same = book.upsert(&[pic(JID, "2")], NOW + 20);
        assert_eq!(same.changed, 0);
        // the fresh file arrives: stale clears, the old file is dropped
        let fresh = avatars::file_name(JID, "2");
        let out = book.upsert(
            &[Update {
                picture_file: Some(fresh.clone()),
                ..pic(JID, "2")
            }],
            NOW + 30,
        );
        assert!(!book.lookup(JID).unwrap().picture_stale);
        assert_eq!(out.drop_files, [name]);
    }

    #[test]
    fn removed_picture_drops_the_file() {
        let mut book = Book::default();
        let name = avatars::file_name(JID, "1");
        book.upsert(
            &[Update {
                picture_file: Some(name.clone()),
                ..pic(JID, "1")
            }],
            NOW,
        );
        let out = book.upsert(
            &[Update {
                picture_removed: true,
                ..upd(JID)
            }],
            NOW + 5,
        );
        assert_eq!(out.drop_files, [name]);
        let p = book.lookup(JID).unwrap();
        assert_eq!(
            (p.picture_id.as_deref(), p.picture_file.as_deref()),
            (None, None)
        );
        assert!(!p.picture_stale);
    }

    #[test]
    fn plan_asks_for_stale_missing_and_never_fetched() {
        let mut book = Book::default();
        book.upsert(&[pic("1@s.whatsapp.net", "a")], NOW); // stale (id, no file)
        book.upsert(
            &[Update {
                on_whatsapp: Some(true),
                ..upd("2@s.whatsapp.net")
            }],
            NOW,
        ); // never asked
        book.upsert(
            &[Update {
                picture_removed: true,
                on_whatsapp: Some(true),
                ..upd("3@s.whatsapp.net")
            }],
            NOW,
        ); // none, fresh
        book.upsert(
            &[Update {
                on_whatsapp: Some(false),
                ..upd("4@s.whatsapp.net")
            }],
            NOW,
        ); // not on WA
        let plan = book.plan(NOW + 1000, &[], 1_000_000);
        let jids: Vec<&str> = plan.refresh.iter().map(|r| r.jid.as_str()).collect();
        assert_eq!(jids, ["1@s.whatsapp.net", "2@s.whatsapp.net"]);
        // "no picture" is asked again after NONE_FOR_MS
        let later = book.plan(NOW + NONE_FOR_MS + 1, &[], 1_000_000);
        assert!(later.refresh.iter().any(|r| r.jid == "3@s.whatsapp.net"));
    }

    #[test]
    fn plan_evicts_orphans_then_the_oldest_over_the_cap() {
        let mut book = Book::default();
        let a = "1@s.whatsapp.net";
        let b = "2@s.whatsapp.net";
        let c = "3@s.whatsapp.net";
        for (jid, id) in [(a, "9"), (b, "9"), (c, "9")] {
            book.upsert(
                &[Update {
                    picture_file: Some(avatars::file_name(jid, id)),
                    ..pic(jid, id)
                }],
                NOW,
            );
        }
        let files = [
            file(&avatars::file_name(a, "8"), 100, 1), // an older picture of a
            file(&avatars::file_name(a, "9"), 1000, 2),
            file(&avatars::file_name(b, "9"), 1000, 3),
            file(&avatars::file_name(c, "9"), 1000, 4),
        ];
        let plan = book.plan(NOW + 1, &files, 2000);
        assert_eq!(plan.remove.len(), 2);
        assert!(
            plan.remove[0].ends_with(&avatars::file_name(a, "8")),
            "orphan first"
        );
        assert!(
            plan.remove[1].ends_with(&avatars::file_name(a, "9")),
            "then the oldest over the cap"
        );
        assert!(plan.refresh.is_empty(), "files present and fresh");
    }

    #[test]
    fn a_missing_file_asks_again() {
        let mut book = Book::default();
        book.upsert(
            &[Update {
                picture_file: Some(avatars::file_name(JID, "1")),
                ..pic(JID, "1")
            }],
            NOW,
        );
        assert!(
            book.plan(NOW + 1, &[file(&avatars::file_name(JID, "1"), 10, 1)], 100)
                .refresh
                .is_empty()
        );
        assert_eq!(book.plan(NOW + 1, &[], 100).refresh.len(), 1);
    }

    #[test]
    fn lid_joins_the_number() {
        let mut book = Book::default();
        book.upsert(
            &[Update {
                push_name: Some("Cem".into()),
                ..upd("555@lid")
            }],
            NOW,
        );
        assert_eq!(book.people[0].jid, "555@lid");
        assert!(book.lookup("+905321234567").is_none());
        // the gateway now tells us the number of that LID
        book.upsert(
            &[Update {
                lid: Some("555@lid".into()),
                number: Some("+90 532 123 45 67".into()),
                ..upd("555@lid")
            }],
            NOW + 1,
        );
        assert_eq!(book.people.len(), 1, "one person");
        let p = book.lookup("+905321234567").unwrap();
        assert_eq!(p.jid, JID);
        assert_eq!(p.name(), Some("Cem"));
        assert_eq!(book.lookup("555@lid").unwrap().jid, JID);
        assert_eq!(book.lookup("555").unwrap().jid, JID);
        assert_eq!(book.lookup("905321234567@s.whatsapp.net").unwrap().jid, JID);
    }

    #[test]
    fn lid_joins_when_the_phone_record_came_first() {
        let mut book = Book::default();
        book.upsert(
            &[Update {
                push_name: Some("Cem".into()),
                ..upd(JID)
            }],
            NOW,
        );
        book.upsert(
            &[Update {
                picture_id: Some("7".into()),
                ..upd("555@lid")
            }],
            NOW + 1,
        );
        assert_eq!(book.people.len(), 2);
        book.upsert(
            &[Update {
                lid: Some("555".into()),
                ..upd(JID)
            }],
            NOW + 2,
        );
        assert_eq!(book.people.len(), 1);
        let p = book.lookup("555@lid").unwrap();
        assert_eq!(p.picture_id.as_deref(), Some("7"));
        assert_eq!(p.name(), Some("Cem"));
    }

    #[test]
    fn offline_read_survives_restart_and_damage() {
        let dir = std::env::temp_dir().join(format!("people-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        assert!(load(&dir).people.is_empty(), "missing file");
        let mut book = Book::default();
        book.upsert(
            &[Update {
                push_name: Some("Ayse".into()),
                ..upd(JID)
            }],
            NOW,
        );
        save(&dir, &book).unwrap();
        assert_eq!(
            load(&dir).lookup("+905321234567").unwrap().name(),
            Some("Ayse")
        );
        fs::write(path(&dir), b"{not json").unwrap();
        assert!(load(&dir).people.is_empty(), "damaged file");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_cache_is_capped() {
        let mut book = Book::default();
        let updates: Vec<Update> = (0..MAX_PEOPLE + 3)
            .map(|i| upd(&format!("1{i:06}@s.whatsapp.net")))
            .collect();
        for (i, u) in updates.iter().enumerate() {
            book.upsert(std::slice::from_ref(u), NOW + i as i64);
        }
        assert_eq!(book.people.len(), MAX_PEOPLE);
        assert!(
            book.lookup("1000000@s.whatsapp.net").is_none(),
            "the oldest went"
        );
    }

    #[test]
    fn snapshot_sorts_by_name_and_puts_unnamed_last() {
        let mut book = Book::default();
        book.upsert(&[upd("9@s.whatsapp.net")], NOW);
        book.upsert(
            &[Update {
                push_name: Some("zeynep".into()),
                ..upd("8@s.whatsapp.net")
            }],
            NOW,
        );
        book.upsert(
            &[Update {
                push_name: Some("Ali".into()),
                ..upd("7@s.whatsapp.net")
            }],
            NOW,
        );
        let names: Vec<_> = book.snapshot().into_iter().map(|r| r.name).collect();
        assert_eq!(names, [Some("Ali".into()), Some("zeynep".into()), None]);
    }
}
