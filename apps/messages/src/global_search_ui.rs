//! The "Messages" section of the Chats search: messages of SMS and of the most
//! recent WhatsApp chats that contain the query, with a highlighted snippet.
//! Matching, snippets, the footer and the jump rules are
//! `rovsti_core::global_search`; this file owns the debounce, the background
//! reads and the rows.

use std::time::Duration;

use gpui::{
    AnyElement, Context, FontWeight, HighlightStyle, InteractiveElement, IntoElement,
    ParentElement, StatefulInteractiveElement, Styled, StyledText, Task, div, px,
};
use rovsti_android::sms;
use rovsti_core::{
    ThreadId,
    global_search::{
        self, JumpTarget, MIN_QUERY_CHARS, SearchCandidate, SearchHit, Source, WA_CHATS,
    },
    sms::SmsMessagesPayload,
};
use rovsti_ui::{
    theme,
    tokens::{Theme, metrics, typography},
};
use rovsti_wa_proto::RichMessage;

use crate::{
    MessagesApp,
    backend::{Pull, decode, now_ms},
};

/// The pause after the last keystroke before the sources are read.
const DEBOUNCE: Duration = Duration::from_millis(300);
/// Messages read from each WhatsApp chat.
const WA_PER_CHAT: u32 = 100;
/// Rows read from the SMS provider.
const SMS_ROWS: usize = 100;

/// A hit and the thread it opens.
pub(crate) struct Hit {
    pub thread: ThreadId,
    pub title: String,
    pub hit: SearchHit,
}

/// One search session of the Chats search.
pub(crate) struct GlobalSearch {
    /// The query the field held at the last edit.
    wanted: String,
    /// The query the hits answer.
    pub query: String,
    pub hits: Vec<Hit>,
    /// Bumped whenever the hits or the state change (the list's layout key).
    pub epoch: u64,
    /// Sources are being read.
    pub running: bool,
    /// WhatsApp history was read for the recent chats only.
    partial: bool,
    sms: Source,
    wa: Source,
    /// Counts edits so a stale debounce timer does nothing.
    typed: u64,
    /// WhatsApp history per chat, kept for the search session.
    wa_cache: Vec<(String, Vec<RichMessage>)>,
    _task: Option<Task<()>>,
}

impl Default for GlobalSearch {
    fn default() -> Self {
        Self {
            wanted: String::new(),
            query: String::new(),
            hits: Vec::new(),
            epoch: 0,
            running: false,
            partial: false,
            sms: Source::Searched,
            wa: Source::Searched,
            typed: 0,
            wa_cache: Vec::new(),
            _task: None,
        }
    }
}

impl GlobalSearch {
    /// Whether the section shows for `query` (already trimmed).
    pub fn shows(query: &str) -> bool {
        query.chars().count() >= MIN_QUERY_CHARS
    }

    /// The search closed: forget everything, including the cached history.
    pub fn reset(&mut self) {
        let epoch = self.epoch + 1;
        *self = Self::default();
        self.epoch = epoch;
    }

    fn footer(&self) -> String {
        if self.running {
            return "Searching…".to_owned();
        }
        global_search::footer(self.hits.len(), self.partial, self.sms, self.wa)
    }
}

/// The thread a `chat_key` of a candidate names (`wa:<jid>`, `sms:<id>`).
fn thread_of(chat_key: &str) -> Option<ThreadId> {
    if let Some(jid) = chat_key.strip_prefix("wa:") {
        return Some(ThreadId::WhatsApp(jid.to_owned()));
    }
    chat_key
        .strip_prefix("sms:")?
        .parse()
        .ok()
        .map(ThreadId::Sms)
}

/// What one background read brings back.
struct Outcome {
    hits: Vec<Hit>,
    sms: Source,
    wa: Source,
    cache: Vec<(String, Vec<RichMessage>)>,
}

/// A message to search, with owned strings.
struct Owned {
    chat_key: String,
    title: String,
    id: String,
    text: String,
    at_ms: i64,
}

fn sms_source<T>(pull: Pull<T>) -> (Option<T>, Source) {
    match pull {
        Pull::Ready(data) => (Some(data), Source::Searched),
        Pull::Denied => (None, Source::NoAccess),
        Pull::Unsupported => (None, Source::NotLinked),
        Pull::Failed(error) => {
            log::warn!("searching SMS failed: {error}");
            (None, Source::Failed)
        }
    }
}

impl MessagesApp {
    /// The Chats search field changed: restarts the debounce when the text did.
    pub(crate) fn on_search_edit(&mut self, cx: &mut Context<Self>) {
        let query = self.search_query(cx);
        if query == self.global.wanted {
            cx.notify();
            return;
        }
        self.global.wanted = query.clone();
        self.global.typed += 1;
        if !GlobalSearch::shows(&query) {
            let epoch = self.global.epoch + 1;
            self.global.hits.clear();
            self.global.running = false;
            self.global._task = None;
            self.global.epoch = epoch;
            cx.notify();
            return;
        }
        self.global.running = true;
        self.global.epoch += 1;
        let token = self.global.typed;
        self.global._task = Some(cx.spawn(async move |this, cx| {
            cx.background_executor().timer(DEBOUNCE).await;
            this.update(cx, |this, cx| {
                if this.global.typed == token {
                    this.run_global_search(query, token, cx);
                }
            })
            .ok();
        }));
        cx.notify();
    }

    /// Reads both sources off the UI thread and combines their hits.
    fn run_global_search(&mut self, query: String, token: u64, cx: &mut Context<Self>) {
        let title_of = |thread: &ThreadId, fallback: String| {
            self.view
                .all_rows()
                .find(|row| row.threads.contains(thread))
                .map_or(fallback, |row| row.title.to_string())
        };
        let wa_chats: Vec<(String, i64)> = self
            .wa_threads
            .iter()
            .filter_map(|t| match &t.id {
                ThreadId::WhatsApp(jid) => Some((jid.clone(), t.last.timestamp_ms)),
                ThreadId::Sms(_) => None,
            })
            .collect();
        let recent = global_search::most_recent(&wa_chats, WA_CHATS);
        let partial = wa_chats.len() > recent.len();
        let wa_titles: Vec<(String, String)> = recent
            .iter()
            .map(|jid| {
                let thread = ThreadId::WhatsApp(jid.clone());
                let fallback = rovsti_wa_ui::display_name(None, None, jid);
                (jid.clone(), title_of(&thread, fallback))
            })
            .collect();
        let sms_titles: Vec<(i64, String)> = self
            .backend
            .read(cx)
            .threads()
            .iter()
            .map(|t| {
                let fallback = t.addresses.join(", ");
                (t.thread_id, title_of(&ThreadId::Sms(t.thread_id), fallback))
            })
            .collect();
        let gateway = self.whatsapp.read(cx).gateway();
        let cache = std::mem::take(&mut self.global.wa_cache);
        let background = cx.background_executor().clone();
        let q = query.clone();
        let task = background.spawn(async move {
            let like = global_search::like_pattern(&q);
            let mut owned: Vec<Owned> = Vec::new();
            let (payload, sms_state) =
                sms_source(decode::<SmsMessagesPayload>(sms::search(&like, SMS_ROWS)));
            let title_for = |thread_id: i64, fallback: &str| {
                sms_titles
                    .iter()
                    .find(|(id, _)| *id == thread_id)
                    .map_or_else(|| fallback.to_owned(), |(_, t)| t.clone())
            };
            for row in payload.iter().flat_map(|p| &p.sms) {
                owned.push(Owned {
                    chat_key: format!("sms:{}", row.thread_id),
                    title: title_for(row.thread_id, &row.address),
                    id: format!("sms:{}", row.id),
                    text: row.body.clone(),
                    at_ms: row.date_ms,
                });
            }
            for row in payload.iter().flat_map(|p| &p.mms) {
                let text: Vec<&str> = row.parts.iter().filter_map(|p| p.text.as_deref()).collect();
                owned.push(Owned {
                    chat_key: format!("sms:{}", row.thread_id),
                    title: title_for(row.thread_id, &row.addresses.join(", ")),
                    id: format!("mms:{}", row.id),
                    text: text.join(" "),
                    at_ms: row.date_ms,
                });
            }
            let mut cache = cache;
            let wa_state = match &gateway {
                None => Source::NotLinked,
                Some(gateway) => {
                    let (mut asked, mut failed) = (0, 0);
                    for (jid, _) in &wa_titles {
                        if cache.iter().any(|(cached, _)| cached == jid) {
                            continue;
                        }
                        asked += 1;
                        match gateway.messages_with_meta(jid, None, WA_PER_CHAT) {
                            Ok(page) => cache.push((jid.clone(), page)),
                            Err(error) => {
                                failed += 1;
                                log::warn!("reading a chat for the search failed: {error}");
                            }
                        }
                    }
                    if asked > 0 && failed == asked {
                        Source::Failed
                    } else {
                        Source::Searched
                    }
                }
            };
            for (jid, title) in &wa_titles {
                let Some((_, page)) = cache.iter().find(|(cached, _)| cached == jid) else {
                    continue;
                };
                for m in page {
                    let Some(text) =
                        crate::conversation::copy_text_of(&m.message.body, Some(&m.meta))
                    else {
                        continue;
                    };
                    owned.push(Owned {
                        chat_key: format!("wa:{jid}"),
                        title: title.clone(),
                        id: m.message.id.clone(),
                        text,
                        at_ms: m.message.timestamp_ms,
                    });
                }
            }
            let candidates: Vec<SearchCandidate<'_>> = owned
                .iter()
                .map(|o| SearchCandidate {
                    chat_key: &o.chat_key,
                    chat_title: &o.title,
                    message_id: &o.id,
                    author: None,
                    text: &o.text,
                    at_ms: o.at_ms,
                })
                .collect();
            let hits = global_search::search(&q, &candidates)
                .into_iter()
                .filter_map(|hit| {
                    Some(Hit {
                        thread: thread_of(&hit.chat_key)?,
                        title: hit.chat_title.clone(),
                        hit,
                    })
                })
                .collect();
            Outcome {
                hits,
                sms: sms_state,
                wa: wa_state,
                cache,
            }
        });
        let task = cx.spawn(async move |this, cx| {
            let outcome = task.await;
            this.update(cx, |this, cx| {
                let global = &mut this.global;
                global.wa_cache = outcome.cache;
                if global.typed != token {
                    return;
                }
                global.query = query;
                global.hits = outcome.hits;
                global.sms = outcome.sms;
                global.wa = outcome.wa;
                global.partial = partial && outcome.wa == Source::Searched;
                global.running = false;
                global.epoch += 1;
                cx.notify();
            })
            .ok();
        });
        self.global._task = Some(task);
    }

    /// A tap on a hit: the thread opens at the message.
    pub(crate) fn open_hit(&mut self, index: usize, cx: &mut Context<Self>) {
        let Some(hit) = self.global.hits.get(index) else {
            return;
        };
        let (thread, title) = (hit.thread.clone(), hit.title.clone());
        self.pending_jump = Some(JumpTarget {
            id: hit.hit.message_id.clone(),
            at_ms: hit.hit.at_ms,
        });
        self.open_thread(thread, title, cx);
        // A thread that did not open (WhatsApp unlinked) must not leave the
        // target for the next chat.
        self.pending_jump = None;
    }

    /// The "Messages" section header.
    pub(crate) fn messages_header(&self, theme: Theme) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .w_full()
            .px(metrics::ROW_INSET)
            .pt(px(12.0))
            .pb(px(4.0))
            .text_size(typography::FOOTNOTE.size)
            .text_color(colors.secondary_label)
            .child("Messages")
            .into_any_element()
    }

    /// The result count line, with the reason for a skipped source.
    pub(crate) fn messages_footer(&self, theme: Theme) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .w_full()
            .px(metrics::ROW_INSET)
            .py(px(8.0))
            .text_size(typography::FOOTNOTE.size)
            .text_color(colors.secondary_label)
            .child(self.global.footer())
            .into_any_element()
    }

    /// One hit: chat title and date, then the snippet with the match in the accent.
    pub(crate) fn hit_row(&self, theme: Theme, index: usize, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let Some(hit) = self.global.hits.get(index) else {
            return div().into_any_element();
        };
        let locale = self.backend.read(cx).locale();
        let time = crate::inbox::row_time(hit.hit.at_ms, now_ms(), locale);
        let accent = HighlightStyle {
            color: Some(colors.accent),
            font_weight: Some(FontWeight::BOLD),
            ..HighlightStyle::default()
        };
        let snippet = StyledText::new(hit.hit.snippet.clone())
            .with_highlights([(hit.hit.highlight.clone(), accent)]);
        div()
            .id(("message-hit", index))
            .w_full()
            .flex()
            .flex_col()
            .gap(px(2.0))
            .px(metrics::ROW_INSET)
            .py(px(8.0))
            .border_b(metrics::SEPARATOR)
            .border_color(colors.separator)
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(12.0))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .truncate()
                            .text_size(typography::HEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(colors.label)
                            .child(hit.title.clone()),
                    )
                    .child(
                        div()
                            .flex_none()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.tertiary_label)
                            .child(time),
                    ),
            )
            .child(
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(colors.secondary_label)
                    .child(snippet),
            )
            .on_click(cx.listener(move |this, _, _, cx| this.open_hit(index, cx)))
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chat_keys_name_threads() {
        assert_eq!(
            thread_of("wa:90555@s.whatsapp.net"),
            Some(ThreadId::WhatsApp("90555@s.whatsapp.net".into()))
        );
        assert_eq!(thread_of("sms:42"), Some(ThreadId::Sms(42)));
        assert_eq!(thread_of("sms:x"), None);
        assert_eq!(thread_of("mms:1"), None);
    }

    #[test]
    fn section_needs_two_characters() {
        assert!(!GlobalSearch::shows(""));
        assert!(!GlobalSearch::shows("a"));
        assert!(GlobalSearch::shows("ab"));
        assert!(GlobalSearch::shows("çö"));
    }

    #[test]
    fn footer_while_running_and_after() {
        let mut g = GlobalSearch {
            running: true,
            ..GlobalSearch::default()
        };
        assert_eq!(g.footer(), "Searching…");
        g.running = false;
        g.sms = Source::NoAccess;
        assert!(g.footer().starts_with("No results. SMS access is off"));
    }
}
