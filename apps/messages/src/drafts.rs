//! Unsent composer text per conversation, kept for the session so Back and
//! return does not lose it. Pure.

use std::collections::HashMap;

use rovsti_core::ThreadId;

#[derive(Default)]
pub struct Drafts(HashMap<ThreadId, String>);

impl Drafts {
    /// Keeps `text` for `thread`. Blank text removes the draft; a thread not
    /// yet created (`Sms` with a negative id) keeps none.
    pub fn put(&mut self, thread: ThreadId, text: &str) {
        if matches!(thread, ThreadId::Sms(id) if id < 0) {
            return;
        }
        if text.trim().is_empty() {
            self.0.remove(&thread);
        } else {
            self.0.insert(thread, text.to_owned());
        }
    }

    /// The draft, kept.
    #[allow(dead_code)]
    pub fn get(&self, thread: &ThreadId) -> Option<&str> {
        self.0.get(thread).map(String::as_str)
    }

    /// The draft, removed.
    pub fn take(&mut self, thread: &ThreadId) -> Option<String> {
        self.0.remove(thread)
    }

    pub fn forget(&mut self, thread: &ThreadId) {
        self.0.remove(thread);
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
}
