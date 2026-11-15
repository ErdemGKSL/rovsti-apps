//! The auto-download rules, as pure functions: what kind of media a message carries and whether its file is
//! fetched by itself, on a tap, or never.
//!
//! The owner's rules: on Wi-Fi images, voice notes and stickers download by themselves; on mobile data images and
//! voice notes; video and documents always wait for a tap; view-once media is never fetched; nothing downloads by
//! itself while roaming, with Data Saver on, offline, or when the device would be left with less than
//! [`FREE_SPACE_FLOOR`] free.

use rosty_wa_proto::MediaMeta;
use serde::{Deserialize, Serialize};

use super::model::Kind;

/// Below this much free space nothing downloads by itself.
pub const FREE_SPACE_FLOOR: u64 = 500 * 1024 * 1024;

/// What the rules tell apart.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaClass {
    Image,
    /// Audio recorded as a voice note.
    VoiceNote,
    /// Any other audio file.
    Audio,
    Video,
    Document,
    Sticker,
    /// Media that can be opened a single time, whatever it is.
    ViewOnce,
    /// A message without a file.
    NotMedia,
}

impl MediaClass {
    /// The class of a message from its kind and what the gateway said about its media.
    pub fn of(kind: Kind, meta: Option<&MediaMeta>) -> MediaClass {
        if !kind.is_media() {
            return MediaClass::NotMedia;
        }
        if meta.is_some_and(|meta| meta.view_once) {
            return MediaClass::ViewOnce;
        }
        match kind {
            Kind::Image => MediaClass::Image,
            Kind::Video => MediaClass::Video,
            Kind::Audio if meta.is_some_and(|meta| meta.voice_note) => MediaClass::VoiceNote,
            Kind::Audio => MediaClass::Audio,
            Kind::Document => MediaClass::Document,
            Kind::Sticker => MediaClass::Sticker,
            Kind::Text | Kind::Location | Kind::Contact | Kind::Unsupported => MediaClass::NotMedia,
        }
    }
}

/// The connection the device is on, as the platform reports it. The caller picks the most restrictive that holds:
/// Data Saver (or a restricted metered network) before roaming, roaming before mobile.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Network {
    Wifi,
    Mobile,
    Roaming,
    DataSaver,
    None,
}

/// What the device is like right now.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Conditions {
    pub network: Network,
    /// Free bytes where the media is kept; `None` when the caller could not tell (the floor is then not applied).
    pub free_bytes: Option<u64>,
}

impl Conditions {
    pub fn on(network: Network) -> Conditions {
        Conditions {
            network,
            free_bytes: None,
        }
    }
}

/// Whether a file is fetched.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Decision {
    /// Download it without being asked.
    Auto,
    /// Download it when the user taps it.
    OnTap,
    /// Never download it into the cache.
    Never,
}

/// The owner's rules for one file of `size` bytes (when the gateway told) under `conditions`.
pub fn policy(class: MediaClass, size: Option<u64>, conditions: &Conditions) -> Decision {
    let by_network = match (class, conditions.network) {
        (MediaClass::ViewOnce | MediaClass::NotMedia, _) => return Decision::Never,
        (MediaClass::Image | MediaClass::VoiceNote | MediaClass::Sticker, Network::Wifi)
        | (MediaClass::Image | MediaClass::VoiceNote, Network::Mobile) => Decision::Auto,
        _ => Decision::OnTap,
    };
    // The floor counts the file itself: a download must not be what takes the device under it.
    let room_left = conditions
        .free_bytes
        .is_none_or(|free| free.saturating_sub(size.unwrap_or(0)) >= FREE_SPACE_FLOOR);
    if by_network == Decision::Auto && !room_left {
        return Decision::OnTap;
    }
    by_network
}

#[cfg(test)]
mod tests {
    use super::*;

    const CLASSES: [MediaClass; 8] = [
        MediaClass::Image,
        MediaClass::VoiceNote,
        MediaClass::Audio,
        MediaClass::Video,
        MediaClass::Document,
        MediaClass::Sticker,
        MediaClass::ViewOnce,
        MediaClass::NotMedia,
    ];
    const NETWORKS: [Network; 5] = [
        Network::Wifi,
        Network::Mobile,
        Network::Roaming,
        Network::DataSaver,
        Network::None,
    ];

    #[test]
    fn the_owners_rules_for_every_class_on_every_network() {
        use Decision::{Auto, Never, OnTap};
        // Columns: wifi, mobile, roaming, data saver, none.
        let table = [
            (MediaClass::Image, [Auto, Auto, OnTap, OnTap, OnTap]),
            (MediaClass::VoiceNote, [Auto, Auto, OnTap, OnTap, OnTap]),
            (MediaClass::Sticker, [Auto, OnTap, OnTap, OnTap, OnTap]),
            (MediaClass::Audio, [OnTap; 5]),
            (MediaClass::Video, [OnTap; 5]),
            (MediaClass::Document, [OnTap; 5]),
            (MediaClass::ViewOnce, [Never; 5]),
            (MediaClass::NotMedia, [Never; 5]),
        ];
        assert_eq!(table.len(), CLASSES.len());
        for (class, row) in table {
            for (network, wanted) in NETWORKS.into_iter().zip(row) {
                for size in [None, Some(1), Some(64 * 1024 * 1024)] {
                    assert_eq!(
                        policy(class, size, &Conditions::on(network)),
                        wanted,
                        "{class:?} on {network:?}, {size:?} bytes"
                    );
                }
            }
        }
    }

    #[test]
    fn nothing_is_automatic_when_free_space_is_under_the_floor() {
        let with = |free| Conditions {
            network: Network::Wifi,
            free_bytes: Some(free),
        };
        let cases = [
            // (free bytes, size, decision for an image)
            (FREE_SPACE_FLOOR, None, Decision::Auto),
            (FREE_SPACE_FLOOR - 1, None, Decision::OnTap),
            (0, None, Decision::OnTap),
            // The file itself must fit above the floor.
            (FREE_SPACE_FLOOR + 1_000, Some(1_000), Decision::Auto),
            (FREE_SPACE_FLOOR + 1_000, Some(1_001), Decision::OnTap),
            (u64::MAX, Some(u64::MAX), Decision::OnTap),
        ];
        for (free, size, wanted) in cases {
            assert_eq!(
                policy(MediaClass::Image, size, &with(free)),
                wanted,
                "{free} free, {size:?} bytes"
            );
        }
        // Low space never turns a tap into a refusal, and never frees view-once media.
        for class in CLASSES {
            let low = policy(class, None, &with(0));
            let roomy = policy(class, None, &with(u64::MAX));
            assert_eq!(
                low == Decision::Never,
                roomy == Decision::Never,
                "{class:?}"
            );
            assert_ne!(low, Decision::Auto, "{class:?}");
        }
    }

    #[test]
    fn a_class_comes_from_the_kind_and_the_media_meta() {
        let meta = |voice_note, view_once| MediaMeta {
            voice_note,
            view_once,
            ..MediaMeta::default()
        };
        let cases = [
            (Kind::Image, None, MediaClass::Image),
            (Kind::Video, None, MediaClass::Video),
            (Kind::Audio, None, MediaClass::Audio),
            (Kind::Audio, Some(meta(true, false)), MediaClass::VoiceNote),
            (Kind::Audio, Some(meta(false, false)), MediaClass::Audio),
            (Kind::Document, None, MediaClass::Document),
            (Kind::Sticker, None, MediaClass::Sticker),
            (Kind::Image, Some(meta(false, true)), MediaClass::ViewOnce),
            (Kind::Audio, Some(meta(true, true)), MediaClass::ViewOnce),
            (Kind::Video, Some(meta(false, true)), MediaClass::ViewOnce),
            (Kind::Text, Some(meta(false, true)), MediaClass::NotMedia),
            (Kind::Location, None, MediaClass::NotMedia),
            (Kind::Contact, None, MediaClass::NotMedia),
            (Kind::Unsupported, None, MediaClass::NotMedia),
        ];
        for (kind, meta, wanted) in cases {
            assert_eq!(
                MediaClass::of(kind, meta.as_ref()),
                wanted,
                "{kind:?} {meta:?}"
            );
        }
    }
}
