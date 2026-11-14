//! WhatsApp Status updates (stories): what the gateway stores apart from the
//! chats, how `GET /v1/statuses` groups them, and the pure model of the
//! full-screen viewer.
//!
//! Status updates reach WhatsApp clients as messages to `status@broadcast`.
//! The gateway keeps them out of every chat list and stores them on their
//! own for [`STATUS_TTL_MS`]; the apps show them on an Updates page.

/// The chat WhatsApp posts status updates to.
pub const STATUS_BROADCAST: &str = "status@broadcast";

/// A status update disappears 24 hours after it was posted.
pub const STATUS_TTL_MS: i64 = 24 * 60 * 60 * 1000;

/// Most ids one "viewed" request may carry.
pub const MAX_STATUS_IDS: usize = 100;

/// How long the viewer shows an update before moving on.
pub const STATUS_SHOW_MS: u64 = 5_000;

/// True for the status broadcast chat.
pub fn is_status_chat(jid: &str) -> bool {
    jid.trim() == STATUS_BROADCAST
}

fn is_false(b: &bool) -> bool {
    !*b
}

/// What a status update shows.
#[derive(
    Clone, Copy, Debug, Default, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize,
)]
#[serde(rename_all = "snake_case")]
pub enum StatusKind {
    /// Text on a coloured background.
    #[default]
    Text,
    Image,
    Video,
    /// Anything else (a voice status, a sticker, a type this gateway does
    /// not know): the viewer shows a label.
    #[serde(other)]
    Other,
}

/// The media of a photo or video status.
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub struct StatusMedia {
    #[serde(default)]
    pub mime: String,
    #[serde(default)]
    pub size: u64,
    /// Length of a video, in seconds.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seconds: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub width: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub height: Option<u32>,
    /// A small preview image is kept (`GET /v1/statuses/{id}/thumbnail`).
    #[serde(default, skip_serializing_if = "is_false")]
    pub thumbnail: bool,
}

/// One status update.
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub struct StatusItem {
    pub id: String,
    /// Who posted it, by phone-number JID when WhatsApp told it (else a LID).
    pub author: String,
    /// Posted from this account (on the phone or another linked device).
    #[serde(default, skip_serializing_if = "is_false")]
    pub from_me: bool,
    pub timestamp_ms: i64,
    /// When it disappears; `timestamp_ms + STATUS_TTL_MS` when absent.
    #[serde(default)]
    pub expires_ms: i64,
    #[serde(default)]
    pub kind: StatusKind,
    /// The text of a text status, or the caption of a photo or video.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    /// Background of a text status, `0xAARRGGBB`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub background_argb: Option<u32>,
    /// Text colour of a text status, `0xAARRGGBB`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text_argb: Option<u32>,
    /// WhatsApp's font number of a text status (0 is the system font).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub font: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub media: Option<StatusMedia>,
    /// The author's push name, as the update carried it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub author_name: Option<String>,
    /// I have seen it (here, or a read receipt went out for it).
    #[serde(default, skip_serializing_if = "is_false")]
    pub viewed: bool,
}

impl StatusItem {
    /// When it disappears.
    pub fn expiry(&self) -> i64 {
        if self.expires_ms > 0 {
            self.expires_ms
        } else {
            self.timestamp_ms.saturating_add(STATUS_TTL_MS)
        }
    }

    pub fn is_expired(&self, now_ms: i64) -> bool {
        self.expiry() <= now_ms
    }
}

/// One person's current status updates.
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub struct StatusAuthor {
    pub author: String,
    /// The newest push name the updates carried.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// Oldest first, the order the viewer plays them in.
    #[serde(default)]
    pub items: Vec<StatusItem>,
}

impl StatusAuthor {
    /// Updates not yet seen.
    pub fn unviewed(&self) -> usize {
        self.items.iter().filter(|i| !i.viewed).count()
    }

    /// When the newest update was posted.
    pub fn latest_ms(&self) -> i64 {
        self.items.iter().map(|i| i.timestamp_ms).max().unwrap_or(0)
    }

    /// Where the viewer starts: the oldest update not yet seen, else the
    /// first.
    pub fn start_index(&self) -> usize {
        self.items.iter().position(|i| !i.viewed).unwrap_or(0)
    }
}

/// What `GET /v1/statuses` answers.
#[derive(Clone, Debug, Default, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
pub struct StatusList {
    /// My own updates, oldest first.
    #[serde(default)]
    pub mine: Vec<StatusItem>,
    /// Everyone else: people with unseen updates first, then by their newest
    /// update, newest first.
    #[serde(default)]
    pub authors: Vec<StatusAuthor>,
}

impl StatusList {
    /// Updates of others not yet seen.
    pub fn unviewed(&self) -> usize {
        self.authors.iter().map(StatusAuthor::unviewed).sum()
    }

    pub fn is_empty(&self) -> bool {
        self.mine.is_empty() && self.authors.is_empty()
    }

    /// Marks `ids` seen; true when one changed. A person whose updates are
    /// all seen now moves down among the seen ones.
    pub fn mark_viewed(&mut self, ids: &[String]) -> bool {
        let mut changed = false;
        for item in self.authors.iter_mut().flat_map(|a| a.items.iter_mut()) {
            if !item.viewed && ids.contains(&item.id) {
                item.viewed = true;
                changed = true;
            }
        }
        if changed {
            sort_authors(&mut self.authors);
        }
        changed
    }
}

/// People with unseen updates first, then by their newest update, newest
/// first (ties by JID, so the order is stable).
fn sort_authors(authors: &mut [StatusAuthor]) {
    authors.sort_by(|a, b| {
        (a.unviewed() == 0)
            .cmp(&(b.unviewed() == 0))
            .then(b.latest_ms().cmp(&a.latest_ms()))
            .then(a.author.cmp(&b.author))
    });
}

/// Groups stored updates for the list: expired ones dropped, mine apart,
/// each author's oldest first, authors with unseen updates first and then
/// newest first (ties by JID, so the order is stable).
pub fn group_statuses(items: Vec<StatusItem>, now_ms: i64) -> StatusList {
    let mut mine = Vec::new();
    let mut authors: Vec<StatusAuthor> = Vec::new();
    for item in items.into_iter().filter(|i| !i.is_expired(now_ms)) {
        if item.from_me {
            mine.push(item);
            continue;
        }
        match authors.iter_mut().find(|a| a.author == item.author) {
            Some(author) => author.items.push(item),
            None => authors.push(StatusAuthor {
                author: item.author.clone(),
                name: None,
                items: vec![item],
            }),
        }
    }
    mine.sort_by(|a, b| (a.timestamp_ms, &a.id).cmp(&(b.timestamp_ms, &b.id)));
    for author in &mut authors {
        author
            .items
            .sort_by(|a, b| (a.timestamp_ms, &a.id).cmp(&(b.timestamp_ms, &b.id)));
        author.name = author
            .items
            .iter()
            .rev()
            .find_map(|i| i.author_name.clone())
            .filter(|n| !n.trim().is_empty());
    }
    sort_authors(&mut authors);
    StatusList { mine, authors }
}

/// `POST /v1/statuses/viewed`: read receipts for status updates. The gateway
/// knows each update's author and sends the receipt to `status@broadcast`
/// with the author as participant.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct MarkStatusViewed {
    pub ids: Vec<String>,
}

/// What a viewer step did.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Step {
    /// Still on the same update.
    Stay,
    /// Another update of the same author.
    Item,
    /// The first update of another author.
    Author,
    /// Past the last update (or before the first): the viewer closes.
    Close,
}

/// The full-screen viewer: which update of which author shows, and how far
/// its timer got. Authors are the list's order; `counts[a]` is how many
/// updates author `a` has. Pure: the caller feeds it elapsed time and taps.
#[derive(Clone, Debug, PartialEq)]
pub struct StatusPlayer {
    counts: Vec<usize>,
    author: usize,
    item: usize,
    elapsed_ms: u64,
    /// How long the current update shows.
    duration_ms: u64,
    paused: bool,
}

impl StatusPlayer {
    /// Starts at update `item` of `author`; `None` when there is nothing to
    /// show there.
    pub fn new(counts: Vec<usize>, author: usize, item: usize, duration_ms: u64) -> Option<Self> {
        let count = *counts.get(author)?;
        (item < count).then_some(Self {
            counts,
            author,
            item,
            elapsed_ms: 0,
            duration_ms: duration_ms.max(1),
            paused: false,
        })
    }

    pub fn author(&self) -> usize {
        self.author
    }

    pub fn item(&self) -> usize {
        self.item
    }

    /// Updates the current author has (the progress bars).
    pub fn count(&self) -> usize {
        self.counts.get(self.author).copied().unwrap_or(0)
    }

    pub fn is_paused(&self) -> bool {
        self.paused
    }

    /// Holding the screen stops the timer.
    pub fn set_paused(&mut self, paused: bool) {
        self.paused = paused;
    }

    /// How long the current update shows (set again after each move, since
    /// a video lasts as long as it is).
    pub fn set_duration(&mut self, duration_ms: u64) {
        self.duration_ms = duration_ms.max(1);
    }

    /// The fill of progress bar `index` of the current author, 0 to 1.
    pub fn fill(&self, index: usize) -> f32 {
        match index.cmp(&self.item) {
            std::cmp::Ordering::Less => 1.0,
            std::cmp::Ordering::Greater => 0.0,
            std::cmp::Ordering::Equal => {
                (self.elapsed_ms as f32 / self.duration_ms as f32).clamp(0.0, 1.0)
            }
        }
    }

    /// Lets `dt_ms` pass; moves on when the update's time is up.
    pub fn advance(&mut self, dt_ms: u64) -> Step {
        if self.paused {
            return Step::Stay;
        }
        self.elapsed_ms = self.elapsed_ms.saturating_add(dt_ms);
        if self.elapsed_ms >= self.duration_ms {
            self.forward()
        } else {
            Step::Stay
        }
    }

    /// A tap on the right: the next update, the next author's first, or close.
    pub fn forward(&mut self) -> Step {
        self.elapsed_ms = 0;
        if self.item + 1 < self.count() {
            self.item += 1;
            return Step::Item;
        }
        match (self.author + 1..self.counts.len()).find(|&a| self.counts[a] > 0) {
            Some(author) => {
                self.author = author;
                self.item = 0;
                Step::Author
            }
            None => Step::Close,
        }
    }

    /// A tap on the left: the previous update, the previous author's last,
    /// or (on the very first) the same update from its start.
    pub fn back(&mut self) -> Step {
        self.elapsed_ms = 0;
        if self.item > 0 {
            self.item -= 1;
            return Step::Item;
        }
        match (0..self.author).rev().find(|&a| self.counts[a] > 0) {
            Some(author) => {
                self.author = author;
                self.item = self.counts[author] - 1;
                Step::Author
            }
            None => Step::Stay,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(id: &str, author: &str, ts: i64) -> StatusItem {
        StatusItem {
            id: id.into(),
            author: author.into(),
            timestamp_ms: ts,
            ..Default::default()
        }
    }

    #[test]
    fn the_broadcast_chat_is_recognised() {
        assert!(is_status_chat("status@broadcast"));
        assert!(is_status_chat(" status@broadcast "));
        assert!(!is_status_chat("123@broadcast"));
        assert!(!is_status_chat("905321234567@s.whatsapp.net"));
    }

    #[test]
    fn expiry_defaults_to_a_day() {
        let mut s = item("a", "1@s.whatsapp.net", 1_000);
        assert_eq!(s.expiry(), 1_000 + STATUS_TTL_MS);
        assert!(!s.is_expired(1_000 + STATUS_TTL_MS - 1));
        assert!(s.is_expired(1_000 + STATUS_TTL_MS));
        s.expires_ms = 5_000;
        assert!(s.is_expired(5_000));
    }

    #[test]
    fn grouping_puts_unseen_first_then_newest() {
        let now = 10 * 3_600_000;
        let mut seen = item("s1", "seen@s.whatsapp.net", now - 10);
        seen.viewed = true;
        let mut mine = item("m1", "me@s.whatsapp.net", now - 50);
        mine.from_me = true;
        let mut named = item("b2", "b@s.whatsapp.net", now - 20);
        named.author_name = Some("Bea".into());
        let items = vec![
            item("a1", "a@s.whatsapp.net", now - 300),
            seen,
            item("b1", "b@s.whatsapp.net", now - 200),
            named,
            mine,
            item("old", "a@s.whatsapp.net", now - STATUS_TTL_MS - 1),
        ];
        let list = group_statuses(items, now);
        assert_eq!(list.mine.len(), 1);
        let order: Vec<&str> = list.authors.iter().map(|a| a.author.as_str()).collect();
        assert_eq!(
            order,
            [
                "b@s.whatsapp.net",
                "a@s.whatsapp.net",
                "seen@s.whatsapp.net"
            ]
        );
        let b = &list.authors[0];
        assert_eq!(b.items[0].id, "b1");
        assert_eq!(b.name.as_deref(), Some("Bea"));
        // The expired update is gone.
        assert_eq!(list.authors[1].items.len(), 1);
        assert_eq!(list.unviewed(), 3);
    }

    #[test]
    fn marking_viewed_moves_the_counter_once() {
        let mut list = group_statuses(vec![item("a1", "a@s.whatsapp.net", 5)], 10);
        assert!(list.mark_viewed(&["a1".into()]));
        assert!(!list.mark_viewed(&["a1".into()]));
        assert_eq!(list.unviewed(), 0);
        assert_eq!(list.authors[0].start_index(), 0);
    }

    #[test]
    fn a_person_seen_in_full_moves_below_the_unseen() {
        let mut list = group_statuses(
            vec![item("a1", "a", 9), item("b1", "b", 5), item("c1", "c", 1)],
            10,
        );
        let order = |l: &StatusList| {
            l.authors
                .iter()
                .map(|a| a.author.clone())
                .collect::<Vec<_>>()
        };
        assert_eq!(order(&list), ["a", "b", "c"]);
        list.mark_viewed(&["a1".into()]);
        assert_eq!(order(&list), ["b", "c", "a"]);
    }

    #[test]
    fn the_viewer_starts_at_the_first_unseen() {
        let mut a = StatusAuthor {
            author: "a".into(),
            name: None,
            items: vec![item("1", "a", 1), item("2", "a", 2)],
        };
        a.items[0].viewed = true;
        assert_eq!(a.start_index(), 1);
    }

    #[test]
    fn the_player_walks_items_and_authors() {
        let mut p = StatusPlayer::new(vec![2, 0, 1], 0, 0, 1_000).unwrap();
        assert_eq!(p.advance(400), Step::Stay);
        assert!((p.fill(0) - 0.4).abs() < 1e-6);
        assert_eq!(p.fill(1), 0.0);
        assert_eq!(p.advance(600), Step::Item);
        assert_eq!((p.author(), p.item()), (0, 1));
        assert_eq!(p.fill(0), 1.0);
        // The empty author is skipped.
        assert_eq!(p.forward(), Step::Author);
        assert_eq!((p.author(), p.item(), p.count()), (2, 0, 1));
        assert_eq!(p.back(), Step::Author);
        assert_eq!((p.author(), p.item()), (0, 1));
        assert_eq!(p.back(), Step::Item);
        assert_eq!(p.back(), Step::Stay);
        assert_eq!((p.author(), p.item()), (0, 0));
        p.set_paused(true);
        assert_eq!(p.advance(5_000), Step::Stay);
        p.set_paused(false);
        assert_eq!(p.forward(), Step::Item);
        assert_eq!(p.forward(), Step::Author);
        assert_eq!(p.forward(), Step::Close);
    }

    #[test]
    fn a_player_needs_something_to_show() {
        assert!(StatusPlayer::new(vec![], 0, 0, 1).is_none());
        assert!(StatusPlayer::new(vec![1], 0, 1, 1).is_none());
        assert!(StatusPlayer::new(vec![1], 0, 0, 1).is_some());
    }

    #[test]
    fn wire_form_is_additive() {
        let json = r#"{"id":"x","author":"a@s.whatsapp.net","timestamp_ms":3}"#;
        let parsed: StatusItem = serde_json::from_str(json).unwrap();
        assert_eq!(parsed.kind, StatusKind::Text);
        assert!(!parsed.viewed);
        let unknown: StatusKind = serde_json::from_str(r#""poll""#).unwrap();
        assert_eq!(unknown, StatusKind::Other);
        let list: StatusList = serde_json::from_str("{}").unwrap();
        assert!(list.is_empty());
        let round = serde_json::to_string(&parsed).unwrap();
        assert_eq!(serde_json::from_str::<StatusItem>(&round).unwrap(), parsed);
    }
}
