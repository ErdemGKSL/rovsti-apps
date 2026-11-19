//! WhatsApp rich content and presence (E6-WA R5): the rules behind polls, contact cards, shared places and the thread
//! header's presence line. No I/O: the gateway, the device store and the apps call these.

mod place;
mod poll;
mod presence;
mod vcard;

pub use place::{
    COORD_DECIMALS, MapTile, PlaceView, STATIC_MAP_ZOOM, StaticMap, coords_text, geo_uri,
    is_valid_coord, place_view, static_map,
};
pub use poll::{
    ChoiceProblem, MAX_OPTION_CHARS, MAX_OPTIONS, MAX_QUESTION_CHARS, MIN_OPTIONS, OptionTally,
    OptionView, PollDraft, PollProblem, PollView, Vote, check_choice, check_draft, latest_votes,
    poll_view, same_voter, selectable_count, tally,
};
pub use presence::{
    HeaderPresence, PresenceSeen, TYPING_TTL_MS, TypingKind, TypingSeen, header_presence,
};
pub use vcard::{
    Card, CardNumber, MAX_VCARD_BYTES, build_vcard, cards_title, parse_vcards, wa_number,
};
