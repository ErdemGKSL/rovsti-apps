//! The bounded ring of recent Rust log lines that Kotlin drains with
//! `core.logs_drain` (people sync v2, D10: pull, never a JNI call per line).
//!
//! Lines are masked ([`rovsti_core::redact::mask`]) before they are kept.
//! Logging never blocks: a line that meets a held lock is dropped, but its
//! sequence number is still used, so the reader sees the gap and counts it.

use std::collections::VecDeque;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU64, Ordering};

use log::Level;
use serde::Serialize;

/// Lines kept in memory.
pub const CAPACITY: usize = 2_000;
/// Longer lines are cut (on a character boundary) to this many bytes.
pub const MAX_LINE: usize = 2_000;
/// Lines one drain answers at most.
pub const MAX_DRAIN: usize = 500;

/// One kept line.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Entry {
    pub seq: u64,
    pub time_ms: i64,
    /// `error`, `warn`, `info`, `debug` or `trace`.
    pub level: &'static str,
    pub target: String,
    pub text: String,
}

/// What [`Ring::drain`] answers.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Drained {
    pub entries: Vec<Entry>,
    /// Lines after `after_seq` (up to `last_seq`) that were lost: pushed out
    /// of the ring before they were read, or dropped while the ring was busy.
    pub dropped: u64,
    /// Pass this as `after_seq` next time.
    pub last_seq: u64,
}

/// The ring itself; the process has one ([`push`], [`drain`]).
#[derive(Debug)]
pub struct Ring {
    capacity: usize,
    entries: VecDeque<Entry>,
    /// The highest sequence number pushed or known lost.
    high: u64,
}

impl Ring {
    pub fn new(capacity: usize) -> Self {
        Self {
            capacity: capacity.max(1),
            entries: VecDeque::new(),
            high: 0,
        }
    }

    pub fn push(&mut self, entry: Entry) {
        self.high = self.high.max(entry.seq);
        if self.entries.len() >= self.capacity {
            self.entries.pop_front();
        }
        self.entries.push_back(entry);
    }

    /// The lines after `after_seq`, oldest first, at most `max`.
    pub fn drain(&self, after_seq: u64, max: usize) -> Drained {
        // Two threads can push out of order; the reader always gets seq order.
        let mut entries: Vec<Entry> = self
            .entries
            .iter()
            .filter(|e| e.seq > after_seq)
            .cloned()
            .collect();
        entries.sort_by_key(|e| e.seq);
        entries.truncate(max);
        let last_seq = match entries.last() {
            Some(last) => last.seq,
            // Nothing new to read: skip what was lost, but never go back.
            None if max > 0 => self.high.max(after_seq),
            None => after_seq,
        };
        let span = last_seq.saturating_sub(after_seq);
        let dropped = span.saturating_sub(entries.len() as u64);
        Drained {
            entries,
            dropped,
            last_seq,
        }
    }
}

static RING: Mutex<Option<Ring>> = Mutex::new(None);
static SEQ: AtomicU64 = AtomicU64::new(0);

/// Whether a record goes to the ring: the app's own targets from Debug,
/// everything else (HTTP and TLS libraries, their URLs) from Warn.
pub fn wanted(target: &str, level: Level) -> bool {
    if target.starts_with("rovsti") {
        level <= Level::Debug
    } else {
        level <= Level::Warn
    }
}

fn level_name(level: Level) -> &'static str {
    match level {
        Level::Error => "error",
        Level::Warn => "warn",
        Level::Info => "info",
        Level::Debug => "debug",
        Level::Trace => "trace",
    }
}

/// `text` cut to at most [`MAX_LINE`] bytes on a character boundary.
fn capped(text: &str) -> &str {
    if text.len() <= MAX_LINE {
        return text;
    }
    let mut end = MAX_LINE;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    &text[..end]
}

fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// Keeps one line (masked). Never blocks: a busy ring drops the line.
pub fn push(level: Level, target: &str, text: &str) {
    if !wanted(target, level) {
        return;
    }
    let seq = SEQ.fetch_add(1, Ordering::Relaxed) + 1;
    let entry = Entry {
        seq,
        time_ms: now_ms(),
        level: level_name(level),
        target: target.to_owned(),
        text: rovsti_core::redact::mask(capped(text)).into_owned(),
    };
    if let Ok(mut ring) = RING.try_lock() {
        ring.get_or_insert_with(|| Ring::new(CAPACITY)).push(entry);
    }
}

/// The process's lines after `after_seq`, at most `max` (capped at
/// [`MAX_DRAIN`]).
pub fn drain(after_seq: u64, max: usize) -> Drained {
    let max = max.min(MAX_DRAIN);
    let ring = RING.lock().unwrap_or_else(|e| e.into_inner());
    match ring.as_ref() {
        Some(ring) => ring.drain(after_seq, max),
        None => Ring::new(1).drain(after_seq, max),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(seq: u64) -> Entry {
        Entry {
            seq,
            time_ms: 0,
            level: "info",
            target: "rovsti::sync".into(),
            text: format!("line {seq}"),
        }
    }

    fn seqs(d: &Drained) -> Vec<u64> {
        d.entries.iter().map(|e| e.seq).collect()
    }

    #[test]
    fn capacity_keeps_the_newest() {
        let mut ring = Ring::new(3);
        for seq in 1..=5 {
            ring.push(entry(seq));
        }
        let all = ring.drain(0, 10);
        assert_eq!(seqs(&all), [3, 4, 5]);
        // Lines 1 and 2 were pushed out before anyone read them.
        assert_eq!(all.dropped, 2);
        assert_eq!(all.last_seq, 5);
    }

    #[test]
    fn drain_after_seq_and_max() {
        let mut ring = Ring::new(10);
        for seq in 1..=6 {
            ring.push(entry(seq));
        }
        let first = ring.drain(2, 2);
        assert_eq!(seqs(&first), [3, 4]);
        assert_eq!((first.dropped, first.last_seq), (0, 4));
        let rest = ring.drain(first.last_seq, 10);
        assert_eq!(seqs(&rest), [5, 6]);
        let none = ring.drain(rest.last_seq, 10);
        assert!(none.entries.is_empty());
        assert_eq!((none.dropped, none.last_seq), (0, 6));
        // A reader ahead of the ring stays where it is.
        let ahead = ring.drain(100, 10);
        assert_eq!((ahead.dropped, ahead.last_seq), (0, 100));
    }

    #[test]
    fn dropped_counter_counts_gaps() {
        let mut ring = Ring::new(10);
        // Line 2 met a busy ring and was dropped; its number was used.
        ring.push(entry(1));
        ring.push(entry(3));
        let d = ring.drain(0, 10);
        assert_eq!(seqs(&d), [1, 3]);
        assert_eq!((d.dropped, d.last_seq), (1, 3));
    }

    #[test]
    fn other_targets_only_from_warn() {
        assert!(wanted("rovsti_ffi::ops", Level::Debug));
        assert!(wanted("rovsti::sync", Level::Info));
        assert!(!wanted("rovsti::sync", Level::Trace));
        assert!(!wanted("ureq::unit", Level::Debug));
        assert!(!wanted("rustls::client", Level::Info));
        assert!(wanted("ureq::unit", Level::Warn));
        assert!(wanted("rustls::client", Level::Error));
    }

    #[test]
    fn long_lines_are_cut_on_a_char_boundary() {
        let long = "ş".repeat(MAX_LINE);
        let cut = capped(&long);
        assert!(cut.len() <= MAX_LINE);
        assert!(cut.chars().all(|c| c == 'ş'));
        assert_eq!(capped("short"), "short");
    }

    #[test]
    fn pushed_lines_are_masked_and_drained() {
        let before = drain(0, MAX_DRAIN).last_seq;
        push(
            Level::Info,
            "rovsti::test",
            "lookup 905321234567@s.whatsapp.net",
        );
        push(Level::Debug, "ureq::unit", "GET https://example.com/?key=x");
        let got = drain(before, MAX_DRAIN);
        let mine: Vec<&Entry> = got
            .entries
            .iter()
            .filter(|e| e.target == "rovsti::test")
            .collect();
        assert_eq!(mine.len(), 1);
        assert_eq!(mine[0].text, "lookup 90***67@s.whatsapp.net");
        assert_eq!(mine[0].level, "info");
        assert!(got.entries.iter().all(|e| e.target != "ureq::unit"));
    }
}
