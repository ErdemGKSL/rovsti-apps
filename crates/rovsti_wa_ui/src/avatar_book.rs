//! The pure half of the WhatsApp avatar loader ([`crate::avatars`]): which
//! profile picture to fetch when, what a fetch result does to the cache, the
//! 429 backoff, and how pictures are named and pruned on disk. No GPUI, no
//! network; the image handle is a type parameter so tests use `()`.
//!
//! A picture is keyed by `(jid, picture id)`. The gateway gives a new id
//! whenever the picture changes (chat meta, `profile` events), so an entry
//! whose id differs from the wanted one is stale and fetched again, while the
//! old image keeps showing until the new one is in.

use std::{
    collections::{HashMap, HashSet},
    path::{Path, PathBuf},
    time::{Instant, SystemTime},
};

/// A fetch that failed for another reason than the rate limit is retried
/// after this long.
pub const RETRY_MS: i64 = 60_000;
/// "No picture" is believed this long before asking again.
pub const NONE_FOR_MS: i64 = 6 * 3_600_000;
/// First and longest wait after a 429.
pub const BACKOFF_MIN_MS: i64 = 5_000;
pub const BACKOFF_MAX_MS: i64 = 300_000;

/// Doubling backoff after `rate_limited` answers; any success resets it.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Backoff {
    delay_ms: i64,
    until_ms: i64,
}

impl Backoff {
    /// A 429 arrived at `now_ms`: wait twice as long as last time.
    pub fn hit(&mut self, now_ms: i64) {
        self.delay_ms = (self.delay_ms * 2).clamp(BACKOFF_MIN_MS, BACKOFF_MAX_MS);
        self.until_ms = now_ms.saturating_add(self.delay_ms);
    }

    pub fn reset(&mut self) {
        *self = Self::default();
    }

    /// Requests must wait at `now_ms`.
    pub fn active(&self, now_ms: i64) -> bool {
        now_ms < self.until_ms
    }

    /// When the wait ends (meaningful while [`Self::active`]).
    pub fn until_ms(&self) -> i64 {
        self.until_ms
    }
}

/// What the background worker should do for one avatar.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Job {
    pub jid: String,
    /// The picture id the caller (or a `profile` event) says is current;
    /// `None`: not known.
    pub wanted: Option<String>,
    /// The id of the picture held in memory, for an `If-None-Match`
    /// revalidation. `None` means: look on disk first.
    pub held: Option<String>,
}

/// What the worker found.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Found<I> {
    /// A picture, from disk or the network. `verified`: the gateway confirmed
    /// it is current (or it came straight from the gateway).
    Image {
        id: String,
        image: I,
        verified: bool,
    },
    /// The gateway still has the picture the job held.
    Unchanged,
    /// No picture (or it is hidden).
    None,
    /// The gateway answered 429; nothing else is known.
    RateLimited,
    /// Network or decode trouble.
    Failed,
}

#[derive(Debug)]
enum Slot<I> {
    Image {
        id: String,
        image: I,
        since: Instant,
        /// `None`: verified this session. Else: revalidate after this time.
        recheck_at_ms: Option<i64>,
    },
    /// No picture as of the wanted id `for_id`, believed until `until_ms`.
    Missing {
        for_id: Option<String>,
        until_ms: i64,
    },
    Failed {
        retry_at_ms: i64,
    },
}

/// Avatars in memory: an LRU of at most `capacity` entries plus what is on
/// its way. Holds the image handles, so dropping an entry frees its pixels.
#[derive(Debug)]
pub struct AvatarBook<I> {
    /// Slot and last use (a counter) by jid.
    slots: HashMap<String, (Slot<I>, u64)>,
    clock: u64,
    capacity: usize,
    pending: HashSet<String>,
    /// Picture ids announced by `profile` / `chat_meta` events; they win over
    /// what the caller passes (the caller's copy may be older).
    hints: HashMap<String, Option<String>>,
    backoff: Backoff,
    max_parallel: usize,
}

impl<I> AvatarBook<I> {
    /// At most `capacity` avatars in memory, `max_parallel` fetches at once.
    pub fn new(capacity: usize, max_parallel: usize) -> Self {
        Self {
            slots: HashMap::new(),
            clock: 0,
            capacity: capacity.max(1),
            pending: HashSet::new(),
            hints: HashMap::new(),
            backoff: Backoff::default(),
            max_parallel: max_parallel.max(1),
        }
    }

    /// The picture of `jid` and when it arrived, if one is held.
    pub fn image(&self, jid: &str) -> Option<(&I, Instant)> {
        match self.slots.get(jid)? {
            (Slot::Image { image, since, .. }, _) => Some((image, *since)),
            _ => None,
        }
    }

    /// The id of the picture held for `jid`.
    pub fn held_id(&self, jid: &str) -> Option<&str> {
        match self.slots.get(jid)? {
            (Slot::Image { id, .. }, _) => Some(id),
            _ => None,
        }
    }

    pub fn is_pending(&self, jid: &str) -> bool {
        self.pending.contains(jid)
    }

    pub fn backoff(&self) -> Backoff {
        self.backoff
    }

    /// Another request to the same bucket (profiles) answered 429.
    pub fn rate_limited(&mut self, now_ms: i64) {
        self.backoff.hit(now_ms);
    }

    /// Marks `jid` as on screen and decides whether a fetch starts now.
    /// `wanted` is the picture id the caller knows (chat meta), `None` when
    /// it knows none. Returns the job and puts `jid` in flight.
    pub fn request(&mut self, jid: &str, wanted: Option<&str>, now_ms: i64) -> Option<Job> {
        let wanted: Option<String> = match self.hints.get(jid) {
            Some(hint) => hint.clone(),
            None => wanted.map(str::to_owned),
        };
        if self.pending.contains(jid) {
            return None;
        }
        self.clock += 1;
        let clock = self.clock;
        let held = match self.slots.get_mut(jid) {
            Some((slot, used)) => {
                *used = clock;
                match slot {
                    Slot::Image {
                        id, recheck_at_ms, ..
                    } => match &wanted {
                        Some(w) if w == id => return None,
                        // Another id is current: fetch it, from disk if it is there.
                        Some(_) => None,
                        None => match recheck_at_ms {
                            Some(at) if *at <= now_ms => Some(id.clone()),
                            _ => return None,
                        },
                    },
                    Slot::Missing { for_id, until_ms } => {
                        if *for_id == wanted && now_ms < *until_ms {
                            return None;
                        }
                        None
                    }
                    Slot::Failed { retry_at_ms } => {
                        if now_ms < *retry_at_ms {
                            return None;
                        }
                        None
                    }
                }
            }
            None => None,
        };
        if self.backoff.active(now_ms) || self.pending.len() >= self.max_parallel {
            return None;
        }
        self.pending.insert(jid.to_owned());
        Some(Job {
            jid: jid.to_owned(),
            wanted,
            held,
        })
    }

    /// A job finished. Returns the images the cache let go of (replaced or
    /// evicted), so the caller can free their GPU copies.
    pub fn arrived(&mut self, job: &Job, found: Found<I>, now_ms: i64) -> Vec<I> {
        self.pending.remove(&job.jid);
        let key = job.jid.clone();
        match found {
            Found::Image {
                id,
                image,
                verified,
            } => {
                if verified {
                    self.backoff.reset();
                }
                let recheck_at_ms = (!verified).then_some(now_ms.saturating_add(RETRY_MS));
                let slot = Slot::Image {
                    id,
                    image,
                    since: Instant::now(),
                    recheck_at_ms,
                };
                self.store(key, slot)
            }
            Found::Unchanged => {
                self.backoff.reset();
                if let Some((Slot::Image { recheck_at_ms, .. }, _)) = self.slots.get_mut(&key) {
                    *recheck_at_ms = None;
                }
                Vec::new()
            }
            Found::None => {
                self.backoff.reset();
                let slot = Slot::Missing {
                    for_id: job.wanted.clone(),
                    until_ms: now_ms.saturating_add(NONE_FOR_MS),
                };
                self.store(key, slot)
            }
            Found::RateLimited => {
                self.backoff.hit(now_ms);
                Vec::new()
            }
            Found::Failed => {
                // A held picture keeps showing; only a miss records the failure.
                if let Some((Slot::Image { recheck_at_ms, .. }, _)) = self.slots.get_mut(&key) {
                    *recheck_at_ms = Some(now_ms.saturating_add(RETRY_MS));
                    return Vec::new();
                }
                self.store(
                    key,
                    Slot::Failed {
                        retry_at_ms: now_ms.saturating_add(RETRY_MS),
                    },
                )
            }
        }
    }

    /// A `profile` or `chat_meta` event names the current picture id of
    /// `jid` (`None`: it has none). A different id makes the held one stale.
    pub fn on_picture_id(&mut self, jid: &str, picture_id: Option<&str>) {
        self.hints
            .insert(jid.to_owned(), picture_id.map(str::to_owned));
        match self.slots.get_mut(jid) {
            Some((
                Slot::Image {
                    id, recheck_at_ms, ..
                },
                _,
            )) => {
                if picture_id != Some(id.as_str()) {
                    // Keep showing it until the fetch answers; ask now.
                    *recheck_at_ms = Some(i64::MIN);
                }
            }
            Some(_) => {
                self.slots.remove(jid);
            }
            None => {}
        }
    }

    /// The gateway or the account changed: forget misses, failures, hints
    /// and the backoff. The pictures themselves stay until refetched.
    pub fn forget_misses(&mut self) {
        self.slots
            .retain(|_, (slot, _)| matches!(slot, Slot::Image { .. }));
        for (slot, _) in self.slots.values_mut() {
            if let Slot::Image { recheck_at_ms, .. } = slot {
                *recheck_at_ms = Some(i64::MIN);
            }
        }
        self.hints.clear();
        self.backoff.reset();
    }

    /// Drops everything (the account was unlinked). Returns the images.
    pub fn clear(&mut self) -> Vec<I> {
        self.hints.clear();
        self.backoff.reset();
        self.slots
            .drain()
            .filter_map(|(_, (slot, _))| match slot {
                Slot::Image { image, .. } => Some(image),
                _ => None,
            })
            .collect()
    }

    fn store(&mut self, key: String, slot: Slot<I>) -> Vec<I> {
        let mut freed = Vec::new();
        self.clock += 1;
        if let Some((Slot::Image { image, .. }, _)) = self.slots.remove(&key) {
            freed.push(image);
        }
        while self.slots.len() >= self.capacity {
            let Some(oldest) = self
                .slots
                .iter()
                .min_by_key(|(_, (_, used))| *used)
                .map(|(k, _)| k.clone())
            else {
                break;
            };
            if let Some((Slot::Image { image, .. }, _)) = self.slots.remove(&oldest) {
                freed.push(image);
            }
        }
        self.slots.insert(key, (slot, self.clock));
        freed
    }
}

// ---- disk ------------------------------------------------------------------

/// Characters kept in file names; everything else becomes `_`.
fn clean(part: &str) -> String {
    part.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_') {
                c
            } else {
                '_'
            }
        })
        .collect()
}

/// `905321234567@s.whatsapp.net` + `171234` → `905321234567_s.whatsapp.net~171234.jpg`.
pub fn file_name(jid: &str, picture_id: &str) -> String {
    format!("{}~{}.jpg", clean(jid), clean(picture_id))
}

/// The picture id a file of `jid` holds, if `name` is one of its files.
pub fn id_of_file(jid: &str, name: &str) -> Option<String> {
    let rest = name.strip_prefix(&clean(jid))?.strip_prefix('~')?;
    let id = rest.strip_suffix(".jpg")?;
    (!id.is_empty()).then(|| id.to_owned())
}

/// A file in the picture directory, for pruning.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DiskFile {
    pub path: PathBuf,
    pub bytes: u64,
    pub modified: SystemTime,
}

/// The files to delete so the rest fit in `max_bytes`: oldest first.
pub fn prune_plan(mut files: Vec<DiskFile>, max_bytes: u64) -> Vec<PathBuf> {
    let mut total: u64 = files.iter().map(|f| f.bytes).sum();
    files.sort_by_key(|f| f.modified);
    let mut out = Vec::new();
    for file in files {
        if total <= max_bytes {
            break;
        }
        total = total.saturating_sub(file.bytes);
        out.push(file.path);
    }
    out
}

/// The stored picture of `jid`: the one with `id` when given, else the
/// newest file of that jid. Blocking file I/O.
pub fn disk_find(dir: &Path, jid: &str, id: Option<&str>) -> Option<(String, PathBuf)> {
    if let Some(id) = id {
        let path = dir.join(file_name(jid, id));
        return path.is_file().then(|| (clean(id), path));
    }
    let mut best: Option<(SystemTime, String, PathBuf)> = None;
    for entry in std::fs::read_dir(dir).ok()?.flatten() {
        let name = entry.file_name();
        let Some(found) = name.to_str().and_then(|n| id_of_file(jid, n)) else {
            continue;
        };
        let modified = entry
            .metadata()
            .and_then(|m| m.modified())
            .unwrap_or(SystemTime::UNIX_EPOCH);
        if best.as_ref().is_none_or(|(at, ..)| modified > *at) {
            best = Some((modified, found, entry.path()));
        }
    }
    best.map(|(_, id, path)| (id, path))
}

/// Drops every stored picture of `jid` except `keep`.
pub fn disk_remove(dir: &Path, jid: &str, keep: Option<&str>) {
    let keep = keep.map(clean);
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        if let Some(id) = name.to_str().and_then(|n| id_of_file(jid, n))
            && Some(&id) != keep.as_ref()
            && let Err(error) = std::fs::remove_file(entry.path())
        {
            log::debug!("removing an old WhatsApp picture failed: {error}");
        }
    }
}

/// Stores `bytes` as the picture `id` of `jid` and drops its older ones.
pub fn disk_store(dir: &Path, jid: &str, id: &str, bytes: &[u8]) -> std::io::Result<()> {
    std::fs::create_dir_all(dir)?;
    let path = dir.join(file_name(jid, id));
    let temp = path.with_extension("part");
    std::fs::write(&temp, bytes)?;
    std::fs::rename(&temp, &path)?;
    disk_remove(dir, jid, Some(id));
    Ok(())
}

/// Deletes the oldest pictures until the directory holds at most `max_bytes`.
pub fn disk_prune(dir: &Path, max_bytes: u64) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    let files = entries
        .flatten()
        .filter_map(|entry| {
            let meta = entry.metadata().ok()?;
            meta.is_file().then(|| DiskFile {
                path: entry.path(),
                bytes: meta.len(),
                modified: meta.modified().unwrap_or(SystemTime::UNIX_EPOCH),
            })
        })
        .collect();
    for path in prune_plan(files, max_bytes) {
        if let Err(error) = std::fs::remove_file(&path) {
            log::debug!("pruning a WhatsApp picture failed: {error}");
        }
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;

    const J: &str = "905321234567@s.whatsapp.net";

    fn book() -> AvatarBook<u8> {
        AvatarBook::new(4, 2)
    }

    fn image(id: &str, verified: bool) -> Found<u8> {
        Found::Image {
            id: id.into(),
            image: 1,
            verified,
        }
    }

    #[test]
    fn a_new_jid_is_fetched_once_until_it_answers() {
        let mut b = book();
        let job = b.request(J, Some("p1"), 0).unwrap();
        assert_eq!(job.wanted.as_deref(), Some("p1"));
        assert_eq!(job.held, None);
        assert!(b.request(J, Some("p1"), 0).is_none(), "already in flight");
        assert!(b.arrived(&job, image("p1", true), 10).is_empty());
        assert!(b.image(J).is_some());
        assert!(b.request(J, Some("p1"), 20).is_none(), "fresh");
    }

    #[test]
    fn a_new_picture_id_refetches_and_keeps_the_old_until_then() {
        let mut b = book();
        let job = b.request(J, Some("p1"), 0).unwrap();
        b.arrived(&job, image("p1", true), 0);
        let job = b.request(J, Some("p2"), 1).unwrap();
        assert_eq!(job.wanted.as_deref(), Some("p2"));
        assert_eq!(b.held_id(J), Some("p1"), "still shown");
        let replaced = b.arrived(&job, image("p2", true), 2);
        assert_eq!(replaced, [1], "the old handle is handed back");
        assert_eq!(b.held_id(J), Some("p2"));
    }

    #[test]
    fn an_unknown_id_revalidates_unverified_pictures_only() {
        let mut b = book();
        let job = b.request(J, None, 0).unwrap();
        // From disk, not yet confirmed by the gateway.
        b.arrived(&job, image("p1", false), 0);
        assert!(b.request(J, None, 10).is_none(), "retry window");
        let job = b.request(J, None, RETRY_MS + 1).unwrap();
        assert_eq!(job.held.as_deref(), Some("p1"));
        b.arrived(&job, Found::Unchanged, RETRY_MS + 2);
        assert!(b.request(J, None, 10 * RETRY_MS).is_none(), "verified");
    }

    #[test]
    fn misses_wait_unless_the_id_changes() {
        let mut b = book();
        let job = b.request(J, None, 0).unwrap();
        b.arrived(&job, Found::None, 0);
        assert!(b.request(J, None, 1_000).is_none());
        assert!(
            b.request(J, Some("new"), 1_000).is_some(),
            "a picture appeared"
        );
        let mut b = book();
        let job = b.request(J, None, 0).unwrap();
        b.arrived(&job, Found::None, 0);
        assert!(
            b.request(J, None, NONE_FOR_MS).is_some(),
            "asks again later"
        );
    }

    #[test]
    fn rate_limits_back_off_and_double() {
        let mut b = book();
        let job = b.request(J, None, 0).unwrap();
        b.arrived(&job, Found::RateLimited, 0);
        assert!(b.backoff().active(1));
        assert!(b.request(J, None, BACKOFF_MIN_MS - 1).is_none());
        let job = b.request(J, None, BACKOFF_MIN_MS).unwrap();
        b.arrived(&job, Found::RateLimited, BACKOFF_MIN_MS);
        assert_eq!(b.backoff().until_ms(), 3 * BACKOFF_MIN_MS);
        let job = b.request(J, None, 3 * BACKOFF_MIN_MS).unwrap();
        b.arrived(&job, image("p", true), 3 * BACKOFF_MIN_MS);
        assert!(!b.backoff().active(3 * BACKOFF_MIN_MS));
    }

    #[test]
    fn backoff_is_capped() {
        let mut backoff = Backoff::default();
        for _ in 0..20 {
            backoff.hit(0);
        }
        assert_eq!(backoff.until_ms(), BACKOFF_MAX_MS);
    }

    #[test]
    fn failures_retry_later_and_parallelism_is_bounded() {
        let mut b = book();
        let a = b.request("a", None, 0).unwrap();
        let _ = b.request("b", None, 0).unwrap();
        assert!(b.request("c", None, 0).is_none(), "two in flight");
        b.arrived(&a, Found::Failed, 0);
        assert!(b.request("a", None, 1).is_none());
        assert!(b.request("c", None, 1).is_some());
        assert!(b.request("a", None, RETRY_MS).is_none(), "slots full again");
    }

    #[test]
    fn profile_events_mark_pictures_stale() {
        let mut b = book();
        let job = b.request(J, Some("p1"), 0).unwrap();
        b.arrived(&job, image("p1", true), 0);
        b.on_picture_id(J, Some("p1"));
        assert!(b.request(J, Some("p1"), 1).is_none(), "same id");
        b.on_picture_id(J, Some("p2"));
        // The caller still passes the old id from its chat list: the hint wins.
        let job = b.request(J, Some("p1"), 2).unwrap();
        assert_eq!(job.wanted.as_deref(), Some("p2"));
        // A removed picture: revalidate the held one (the gateway says 404).
        b.arrived(&job, image("p2", true), 3);
        b.on_picture_id(J, None);
        let job = b.request(J, Some("p2"), 4).unwrap();
        assert_eq!(job.wanted, None);
        assert_eq!(job.held.as_deref(), Some("p2"));
        b.arrived(&job, Found::None, 5);
        assert!(b.image(J).is_none());
    }

    #[test]
    fn the_lru_is_capped() {
        let mut b: AvatarBook<u8> = AvatarBook::new(2, 8);
        for jid in ["a", "b", "c"] {
            let job = b.request(jid, Some("1"), 0).unwrap();
            b.arrived(&job, image("1", true), 0);
        }
        assert!(b.image("a").is_none());
        assert!(b.image("c").is_some());
    }

    #[test]
    fn forgetting_misses_keeps_images() {
        let mut b = book();
        let job = b.request("a", Some("1"), 0).unwrap();
        b.arrived(&job, image("1", true), 0);
        let job = b.request("b", None, 0).unwrap();
        b.arrived(&job, Found::None, 0);
        b.on_picture_id("b", None);
        b.forget_misses();
        assert!(b.image("a").is_some());
        assert!(b.request("b", None, 1).is_some());
    }

    #[test]
    fn file_names_round_trip() {
        let name = file_name(J, "1712:34");
        assert_eq!(name, "905321234567_s.whatsapp.net~1712_34.jpg");
        assert_eq!(id_of_file(J, &name).as_deref(), Some("1712_34"));
        assert_eq!(id_of_file("other@s.whatsapp.net", &name), None);
        assert_eq!(id_of_file(J, "905321234567_s.whatsapp.net~.jpg"), None);
        assert_eq!(file_name("../x", "/"), ".._x~_.jpg");
    }

    #[test]
    fn prune_drops_the_oldest_until_it_fits() {
        let at = |s: u64| SystemTime::UNIX_EPOCH + Duration::from_secs(s);
        let file = |name: &str, bytes, s| DiskFile {
            path: PathBuf::from(name),
            bytes,
            modified: at(s),
        };
        let plan = prune_plan(
            vec![file("new", 40, 3), file("old", 40, 1), file("mid", 40, 2)],
            80,
        );
        assert_eq!(plan, [PathBuf::from("old")]);
        assert!(prune_plan(vec![file("a", 10, 1)], 80).is_empty());
    }

    #[test]
    fn disk_store_find_and_remove() {
        let dir = std::env::temp_dir().join(format!(
            "rovsti-avatars-test-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .map_or(0, |d| d.as_nanos())
        ));
        disk_store(&dir, J, "1", b"one").unwrap();
        assert_eq!(
            disk_find(&dir, J, Some("1")).map(|(id, _)| id),
            Some("1".into())
        );
        disk_store(&dir, J, "2", b"two").unwrap();
        assert!(
            disk_find(&dir, J, Some("1")).is_none(),
            "the old one is gone"
        );
        let (id, path) = disk_find(&dir, J, None).unwrap();
        assert_eq!(id, "2");
        assert_eq!(std::fs::read(path).unwrap(), b"two");
        disk_store(&dir, "other@s.whatsapp.net", "9", b"x").unwrap();
        disk_remove(&dir, J, None);
        assert!(disk_find(&dir, J, None).is_none());
        assert!(disk_find(&dir, "other@s.whatsapp.net", None).is_some());
        disk_prune(&dir, 0);
        assert!(disk_find(&dir, "other@s.whatsapp.net", None).is_none());
        std::fs::remove_dir_all(&dir).ok();
    }
}
