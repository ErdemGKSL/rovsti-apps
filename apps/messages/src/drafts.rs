//! Unsent composer text per conversation, kept for the session so Back and
//! return does not lose it. Pure.

use std::collections::{BTreeMap, HashMap};

use rovsti_core::ThreadId;

/// The file the drafts are kept in (a map of thread tag to text).
#[cfg_attr(not(test), allow(dead_code))]
pub const DRAFTS_FILE: &str = "drafts.json";

#[derive(Default)]
pub struct Drafts {
    map: HashMap<ThreadId, String>,
    rev: u64,
}

impl Drafts {
    /// Keeps `text` for `thread`. Blank text removes the draft; a thread not
    /// yet created (`Sms` with a negative id) keeps none.
    pub fn put(&mut self, thread: ThreadId, text: &str) {
        if matches!(thread, ThreadId::Sms(id) if id < 0) {
            return;
        }
        let changed = if text.trim().is_empty() {
            self.map.remove(&thread).is_some()
        } else {
            self.map.insert(thread, text.to_owned()).as_deref() != Some(text)
        };
        if changed {
            self.rev += 1;
        }
    }

    /// Bumped whenever a draft is added, changed or removed.
    pub fn rev(&self) -> u64 {
        self.rev
    }

    /// The draft, kept.
    pub fn get(&self, thread: &ThreadId) -> Option<&str> {
        self.map.get(thread).map(String::as_str)
    }

    /// The draft, removed.
    pub fn take(&mut self, thread: &ThreadId) -> Option<String> {
        let taken = self.map.remove(thread);
        if taken.is_some() {
            self.rev += 1;
        }
        taken
    }

    /// The drafts as the file keeps them, keyed by conversation tag.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn to_map(&self) -> BTreeMap<String, String> {
        self.map
            .iter()
            .map(|(thread, text)| (crate::notifications::thread_tag(thread), text.clone()))
            .collect()
    }

    /// Reads [`Drafts::to_map`] back; bad tags, new-thread ids and blank texts are skipped.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn from_map(map: &BTreeMap<String, String>) -> Drafts {
        let mut drafts = Drafts::default();
        for (tag, text) in map {
            if let Some(thread) = crate::notifications::parse_tag(tag) {
                drafts.put(thread, text);
            }
        }
        drafts.rev = 0;
        drafts
    }

    /// Adds drafts from `loaded` for threads without one now; this session's text wins.
    #[cfg_attr(not(test), allow(dead_code))]
    pub fn merge_loaded(&mut self, loaded: Drafts) {
        let mut added = false;
        for (thread, text) in loaded.map {
            if let std::collections::hash_map::Entry::Vacant(slot) = self.map.entry(thread) {
                slot.insert(text);
                added = true;
            }
        }
        if added {
            self.rev += 1;
        }
    }

    pub fn forget(&mut self, thread: &ThreadId) {
        self.take(thread);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wa() -> ThreadId {
        ThreadId::WhatsApp("1@s.whatsapp.net".into())
    }

    #[test]
    fn put_and_take_round_trip() {
        let mut d = Drafts::default();
        d.put(wa(), "hello");
        d.put(ThreadId::Sms(3), "sms");
        assert_eq!(d.take(&wa()).as_deref(), Some("hello"));
        assert_eq!(d.take(&wa()), None, "take empties");
        assert_eq!(d.take(&ThreadId::Sms(3)).as_deref(), Some("sms"));
    }

    #[test]
    fn rev_moves_only_on_real_changes() {
        let mut d = Drafts::default();
        d.put(wa(), "  ");
        assert_eq!(d.rev(), 0);
        d.put(wa(), "hi");
        assert_eq!(d.rev(), 1);
        d.put(wa(), "hi");
        assert_eq!(d.rev(), 1);
        d.put(wa(), "hi!");
        assert_eq!(d.rev(), 2);
        d.forget(&wa());
        assert_eq!(d.rev(), 3);
        d.forget(&wa());
        assert_eq!(d.rev(), 3);
    }

    #[test]
    fn get_peeks_without_removing() {
        let mut d = Drafts::default();
        assert_eq!(d.get(&wa()), None);
        d.put(wa(), "hello");
        assert_eq!(d.get(&wa()), Some("hello"));
        assert_eq!(d.get(&wa()), Some("hello"));
        assert_eq!(d.take(&wa()).as_deref(), Some("hello"));
        assert_eq!(d.get(&wa()), None);
    }

    #[test]
    fn blank_removes_and_forget_clears() {
        let mut d = Drafts::default();
        d.put(wa(), "hello");
        d.put(wa(), "  \n");
        assert_eq!(d.take(&wa()), None);
        d.put(wa(), "again");
        d.forget(&wa());
        assert_eq!(d.take(&wa()), None);
    }

    #[test]
    fn a_negative_sms_id_is_ignored() {
        let mut d = Drafts::default();
        d.put(ThreadId::Sms(-1), "new");
        assert_eq!(d.take(&ThreadId::Sms(-1)), None);
    }

    #[test]
    fn drafts_round_trip_through_the_file_map() {
        let mut d = Drafts::default();
        d.put(wa(), "hello");
        d.put(ThreadId::Sms(3), "sms");
        let map = d.to_map();
        assert_eq!(map.get("sms:3").map(String::as_str), Some("sms"));
        assert_eq!(
            map.get("wa:1@s.whatsapp.net").map(String::as_str),
            Some("hello")
        );
        assert_eq!(DRAFTS_FILE, "drafts.json");
        let back = Drafts::from_map(&map);
        assert_eq!(back.rev(), 0);
        assert_eq!(back.get(&wa()), Some("hello"));
        assert_eq!(back.get(&ThreadId::Sms(3)), Some("sms"));
    }

    #[test]
    fn bad_tags_are_skipped() {
        let map: BTreeMap<String, String> = [
            ("junk", "x"),
            ("sms:abc", "x"),
            ("sms:-1", "x"),
            ("wa:", "x"),
            ("sms:4", "  "),
            ("sms:5", "ok"),
        ]
        .into_iter()
        .map(|(k, v)| (k.to_owned(), v.to_owned()))
        .collect();
        let d = Drafts::from_map(&map);
        assert_eq!(d.to_map().len(), 1);
        assert_eq!(d.get(&ThreadId::Sms(5)), Some("ok"));
    }

    #[test]
    fn session_drafts_win_over_loaded_ones() {
        let mut d = Drafts::default();
        d.put(wa(), "new");
        let rev = d.rev();
        let mut loaded = Drafts::default();
        loaded.put(wa(), "old");
        d.merge_loaded(loaded);
        assert_eq!(d.get(&wa()), Some("new"));
        assert_eq!(d.rev(), rev, "nothing added");
        let mut loaded = Drafts::default();
        loaded.put(ThreadId::Sms(2), "from file");
        d.merge_loaded(loaded);
        assert_eq!(d.get(&ThreadId::Sms(2)), Some("from file"));
        assert_eq!(d.rev(), rev + 1);
    }
}
