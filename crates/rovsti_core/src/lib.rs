//! Platform-free domain types shared by Rovsti Phone, Messages, Contacts and Stops.
//!
//! Nothing in this crate touches GPUI, JNI or the network, so it builds and
//! tests on any host in seconds.

pub mod audio;
pub mod avatar;
/// Swipe-to-reply and double-tap gesture math for chat bubbles.
pub mod birthday_reminder;
pub mod blocklist;
pub mod bubble_gesture;
pub mod call;
pub mod call_log;
pub mod call_log_query;
pub mod call_notes;
pub mod call_stats;
pub mod calling_code;
pub mod chat_export;
pub mod chat_find;
pub mod chat_gallery;
pub mod contact;
pub mod contact_card;
/// Clean up filters: contacts without a number, name, photo or group.
pub mod contact_cleanup;
/// Birthdays and anniversaries: parsing, next occurrence, upcoming list.
pub mod contact_dates;
/// Duplicate detection and merging of contacts.
pub mod contact_dupes;
pub mod contact_edit;
/// Calls with one contact on the card: limits, header, permission text.
pub mod contact_history;
/// Contacts shared to the app: .vcf files and text.
pub mod contact_intake;
/// Per-contact ringtone and voicemail options, and the Home screen shortcut spec.
pub mod contact_options;
pub mod contact_photo;
/// Select mode in the Contacts list: export ids, group changes, notices, contact text.
/// Quick actions on a contact row.
pub mod contact_quick;
/// Scan to add: QR decoding and the payload parser.
pub mod contact_scan;
pub mod contact_select;
/// Counts over the whole address book.
pub mod contact_stats;
/// Local trash for deleted contacts: retention, restore, texts.
pub mod contact_trash;
pub mod contacts_prefs;
pub mod contacts_view;
/// Header copy choices: name, number or WhatsApp ID.
pub mod copy_choices;
pub mod date_detect;
pub mod date_jump;
pub mod dial;
pub mod forward;
pub mod gesture;
pub mod global_search;
/// Group utilities: SMS and email recipients, .vcf file name.
pub mod group_tools;
pub mod import_plan;
pub mod inbox;
/// Bulk actions for multi-selected inbox rows: availability, pin caps, notices.
pub mod inbox_bulk;
/// Inbox filter chips: matching, counts, visibility and labels.
pub mod inbox_filter;
pub mod keypad_layout;
/// OpenStreetMap links and share text for locations.
pub mod location_link;
pub mod lru;
pub mod message_info;
/// Reminders attached to a single message.
pub mod message_reminder;
pub mod mms_pdu;
pub mod msg_backup;
pub mod multi_select;
pub mod mute;
pub mod names;
pub mod notify;
pub mod number_format;
pub mod otp;
pub mod pending_delete;
pub mod perf;
pub mod phone_number;
pub mod quick_replies;
pub mod recents;
pub mod reminder;
pub mod scene;
pub mod schedule;
pub mod search;
pub mod settings;
pub mod setup;
pub mod shared_card;
pub mod sim;
pub mod small_text;
pub mod sms;
pub mod sms_timeline;
pub mod spam;
pub mod speed_dial;
pub mod starred;
pub mod t9;
pub mod text_detect;
/// Chat text size setting.
pub mod text_scale;
pub mod thread;
/// Local pinned, archived and unread marks for SMS threads.
pub mod thread_marks;
pub mod unread_jump;
pub mod vcard;
pub mod voice_player;
pub mod wire;
pub mod zoom;

pub use contact::{Contact, ContactId};
pub use phone_number::{PhoneNumber, PhoneNumberError};
pub use thread::{Channel, Message, MessageDirection, ThreadId};
