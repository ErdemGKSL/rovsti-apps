//! WhatsApp message actions (E6-WA R4): the rules behind edit, forward, mentions, link previews, message info and
//! disappearing messages. No I/O: the gateway, the device store and the apps call these.
//!
//! Offsets handed to the apps are UTF-16 code units, the unit of a Kotlin `String` (as `otp.find` does).

mod disappearing;
mod edit;
mod forward;
mod link;
mod mention;
mod receipts;
mod utf16;

pub use disappearing::{
    DISAPPEARING_CHOICES, Timer, expires_at, is_disappearing_choice, is_expired, timer_of,
};
pub use edit::{EDIT_WINDOW_MS, EditTarget, edit_text_ok, editable_until};
pub use forward::{
    FREQUENTLY_FORWARDED_SCORE, ForwardRefusal, ForwardSource, can_forward, forwarded_many,
};
pub use link::{
    FoundUrl, LinkMeta, MAX_DESCRIPTION_CHARS, MAX_HTML_BYTES, MAX_TITLE_CHARS, first_url,
    host_of, is_fetchable, parse_html, resolve_url,
};
pub use mention::{
    Encoded, MentionQuery, MentionSpan, MentionTarget, Pick, addresses_me, encode, mention_token,
    query_at, spans,
};
pub use receipts::{MemberReceipt, ReceiptRow, ReceiptSection, SectionRows, sections};
