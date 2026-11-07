//! Stops settings. Every struct is `#[serde(default)]` so files written by
//! older versions keep loading.

use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::NoticeRule;
use crate::alarm::{AlarmPlan, MIN_ALARM_VOLUME_PCT, VibrationPattern};
use crate::notice::{DEFAULT_TEMPLATE, TemplateError, validate};
use crate::session::NoticeState;
use crate::tiles::TileSource;

/// Snooze lengths the Settings row cycles through, minutes.
pub const SNOOZE_CHOICES: [u8; 5] = [1, 3, 5, 10, 15];
/// Tile cache size choices the Settings row cycles through, megabytes.
pub const TILE_CACHE_CHOICES_MB: [u32; 4] = [128, 256, 512, 1024];
/// "Minutes before" choices for the arriving-soon message.
pub const NOTICE_MINUTES: [u8; 3] = [5, 10, 15];

/// When the "arriving soon" message goes out.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum NoticeWhen {
    /// Together with the alarm.
    #[default]
    WithAlarm,
    /// This many minutes before the circle is reached.
    MinutesBefore(u8),
}

impl NoticeWhen {
    /// The choices in the order the page lists them.
    pub const ALL: [Self; 4] = [
        Self::WithAlarm,
        Self::MinutesBefore(NOTICE_MINUTES[0]),
        Self::MinutesBefore(NOTICE_MINUTES[1]),
        Self::MinutesBefore(NOTICE_MINUTES[2]),
    ];

    pub fn label(self) -> String {
        match self {
            Self::WithAlarm => "When you cross the line".to_owned(),
            Self::MinutesBefore(m) => format!("{m} min before the circle"),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct NoticePrefs {
    /// WhatsApp chat id on the gateway.
    pub chat: String,
    /// Chat name for display.
    pub chat_name: String,
    pub template: String,
    pub when: NoticeWhen,
}

impl Default for NoticePrefs {
    fn default() -> Self {
        Self {
            chat: String::new(),
            chat_name: String::new(),
            template: DEFAULT_TEMPLATE.to_owned(),
            when: NoticeWhen::default(),
        }
    }
}

impl NoticePrefs {
    /// Takes `text` as the template when it passes [`validate`]; otherwise
    /// the old template stays and the reason comes back.
    pub fn set_template(&mut self, text: &str) -> Result<(), TemplateError> {
        validate(text)?;
        self.template = text.trim().to_owned();
        Ok(())
    }

    /// The notice for a new trip. "When you cross the line" goes out with the
    /// crossing; "N minutes before" when the circle is that far away.
    pub fn to_notice_state(&self) -> NoticeState {
        let rule = match self.when {
            NoticeWhen::WithAlarm => NoticeRule::WithAlarm,
            NoticeWhen::MinutesBefore(m) => NoticeRule::BeforeCircle {
                before: Duration::from_secs(u64::from(m.max(1)) * 60),
            },
        };
        NoticeState {
            chat: self.chat.clone(),
            text_template: self.template.clone(),
            rule,
            sent: false,
            attempts: 0,
        }
    }
}

/// Map and app appearance.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Appearance {
    #[default]
    Auto,
    Light,
    Dark,
}

impl Appearance {
    pub const ALL: [Self; 3] = [Self::Auto, Self::Light, Self::Dark];

    pub fn label(self) -> &'static str {
        match self {
            Self::Auto => "Auto",
            Self::Light => "Light",
            Self::Dark => "Dark",
        }
    }

    /// The next choice (wrapping).
    pub fn next(self) -> Self {
        let i = Self::ALL.iter().position(|a| *a == self).unwrap_or(0);
        Self::ALL[(i + 1) % Self::ALL.len()]
    }
}

/// A sound the user picked for the alarm.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct AlarmSound {
    /// Content URI of the sound.
    pub uri: String,
    /// Name to show.
    pub title: String,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct StopsPrefs {
    pub sound: bool,
    pub vibrate: bool,
    pub snooze_min: u8,
    /// `None`: the arriving-soon message is off.
    pub notice: Option<NoticePrefs>,
    /// Map tile URL template; `None` is the default public server.
    pub tile_url: Option<String>,
    pub appearance: Appearance,
    pub vibration: VibrationPattern,
    /// Alarm volume, percent of the alarm stream (80..=100).
    pub volume_pct: u8,
    /// `None`: the system default alarm sound.
    pub alarm_sound: Option<AlarmSound>,
    /// Tile cache cap, megabytes (one of [`TILE_CACHE_CHOICES_MB`]).
    #[serde(default = "default_tile_cache_mb")]
    pub tile_cache_mb: u32,
}

fn default_tile_cache_mb() -> u32 {
    TILE_CACHE_CHOICES_MB[0]
}

impl Default for StopsPrefs {
    fn default() -> Self {
        Self {
            sound: true,
            vibrate: true,
            snooze_min: 5,
            notice: None,
            tile_url: None,
            appearance: Appearance::default(),
            vibration: VibrationPattern::default(),
            volume_pct: MIN_ALARM_VOLUME_PCT,
            alarm_sound: None,
            tile_cache_mb: default_tile_cache_mb(),
        }
    }
}

impl StopsPrefs {
    /// True when every alarm setting equals its default.
    pub fn alarm_is_default(&self) -> bool {
        let d = Self::default();
        self.sound == d.sound
            && self.vibrate == d.vibrate
            && self.snooze_min == d.snooze_min
            && self.vibration == d.vibration
            && self.volume_pct == d.volume_pct
            && self.alarm_sound == d.alarm_sound
    }

    /// A copy with only the alarm settings reset to their defaults.
    pub fn with_alarm_defaults(&self) -> StopsPrefs {
        let d = Self::default();
        StopsPrefs {
            sound: d.sound,
            vibrate: d.vibrate,
            snooze_min: d.snooze_min,
            vibration: d.vibration,
            volume_pct: d.volume_pct,
            alarm_sound: d.alarm_sound,
            ..self.clone()
        }
    }

    /// The chosen tile server; a missing or invalid stored value is the default.
    pub fn tile_source(&self) -> TileSource {
        self.tile_url
            .as_deref()
            .and_then(|u| TileSource::parse(u).ok())
            .unwrap_or_default()
    }

    /// Stores `s`; the default server is stored as `None`.
    pub fn set_tile_url(&mut self, s: &TileSource) {
        self.tile_url = (!s.is_default()).then(|| s.template().to_owned());
    }

    /// Flips the alarm sound. The last of sound and vibration cannot be
    /// turned off (a silent alarm would not wake anyone): returns `false`
    /// and changes nothing then.
    pub fn toggle_sound(&mut self) -> bool {
        if self.sound && !self.vibrate {
            return false;
        }
        self.sound = !self.sound;
        true
    }

    /// Flips the vibration, with the same rule as [`Self::toggle_sound`].
    pub fn toggle_vibrate(&mut self) -> bool {
        if self.vibrate && !self.sound {
            return false;
        }
        self.vibrate = !self.vibrate;
        true
    }

    /// What Kotlin rings with for a new trip.
    pub fn alarm_plan(&self) -> AlarmPlan {
        AlarmPlan {
            volume_pct: self.volume_pct.clamp(MIN_ALARM_VOLUME_PCT, 100),
            vibrate_pattern_ms: self.vibration.pattern_ms().to_vec(),
            user_sound_uri: self.alarm_sound.as_ref().map(|s| s.uri.clone()),
            ..AlarmPlan::default()
        }
    }

    /// JSON for a test ring: the plan plus the sound and vibration switches.
    pub fn test_ring_json(&self) -> String {
        let mut v = serde_json::to_value(self.alarm_plan()).unwrap_or_default();
        if let Some(o) = v.as_object_mut() {
            o.insert("sound".to_owned(), self.sound.into());
            o.insert("vibrate".to_owned(), self.vibrate.into());
        }
        v.to_string()
    }

    /// Name of the alarm sound for the Settings row.
    pub fn alarm_sound_label(&self) -> String {
        self.alarm_sound
            .as_ref()
            .map_or_else(|| "Default alarm".to_owned(), |s| s.title.clone())
    }

    /// The next cache size in [`TILE_CACHE_CHOICES_MB`] (wrapping); a stored
    /// value not on the list snaps to the first choice.
    pub fn next_tile_cache(&self) -> u32 {
        TILE_CACHE_CHOICES_MB
            .iter()
            .position(|m| *m == self.tile_cache_mb)
            .map_or(TILE_CACHE_CHOICES_MB[0], |i| {
                TILE_CACHE_CHOICES_MB[(i + 1) % TILE_CACHE_CHOICES_MB.len()]
            })
    }

    /// The tile cache cap in bytes.
    pub fn tile_cache_bytes(&self) -> u64 {
        u64::from(self.tile_cache_mb) * 1024 * 1024
    }

    /// The next snooze length in [`SNOOZE_CHOICES`] (wrapping); a stored
    /// value outside the list moves to the first choice that is longer.
    pub fn next_snooze(&self) -> u8 {
        SNOOZE_CHOICES
            .into_iter()
            .find(|m| *m > self.snooze_min)
            .unwrap_or(SNOOZE_CHOICES[0])
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn sound_and_vibration_are_never_both_off() {
        let mut p = StopsPrefs::default();
        assert!(p.toggle_sound());
        assert!(!p.sound && p.vibrate);
        assert!(!p.toggle_vibrate());
        assert!(p.vibrate);
        assert!(p.toggle_sound());
        assert!(p.toggle_vibrate());
        assert!(p.sound && !p.vibrate);
    }

    #[test]
    fn default_prefs_give_default_plan() {
        let p = StopsPrefs::default();
        assert_eq!(p.alarm_plan(), AlarmPlan::default());
        assert_eq!(p.alarm_sound_label(), "Default alarm");
        assert_eq!(p.appearance, Appearance::Auto);
    }

    #[test]
    fn old_prefs_json_loads() {
        let p: StopsPrefs = serde_json::from_str(r#"{"sound":true,"snooze_min":3}"#).unwrap();
        assert_eq!(p.snooze_min, 3);
        assert_eq!(p.volume_pct, 80);
        assert_eq!(p.vibration, VibrationPattern::Standard);
        assert!(p.alarm_sound.is_none());
    }

    #[test]
    fn volume_is_clamped_in_plan() {
        let mut p = StopsPrefs::default();
        for (given, want) in [(0, 80), (79, 80), (90, 90), (255, 100)] {
            p.volume_pct = given;
            assert_eq!(p.alarm_plan().volume_pct, want);
        }
    }

    #[test]
    fn appearance_cycles() {
        let mut a = Appearance::Auto;
        for want in [Appearance::Light, Appearance::Dark, Appearance::Auto] {
            a = a.next();
            assert_eq!(a, want);
            assert!(!a.label().is_empty());
        }
    }

    #[test]
    fn test_ring_json_keys() {
        let mut p = StopsPrefs {
            vibrate: false,
            ..StopsPrefs::default()
        };
        let v: serde_json::Value = serde_json::from_str(&p.test_ring_json()).unwrap();
        assert_eq!(v["sound"], true);
        assert_eq!(v["vibrate"], false);
        assert_eq!(v["volume_pct"], 80);
        assert!(v.get("vibrate_pattern_ms").is_some());
        assert!(v.get("user_sound_uri").is_none());
        p.vibration = VibrationPattern::Long;
        p.alarm_sound = Some(AlarmSound {
            uri: "content://x/1".into(),
            title: "Chime".into(),
        });
        let v: serde_json::Value = serde_json::from_str(&p.test_ring_json()).unwrap();
        assert_eq!(v["user_sound_uri"], "content://x/1");
        assert_eq!(v["vibrate_pattern_ms"], serde_json::json!([0, 1500, 700]));
        assert_eq!(p.alarm_sound_label(), "Chime");
    }

    #[test]
    fn snooze_cycles_and_wraps() {
        let mut p = StopsPrefs::default();
        let mut seen = Vec::new();
        for _ in 0..6 {
            p.snooze_min = p.next_snooze();
            seen.push(p.snooze_min);
        }
        assert_eq!(seen, [10, 15, 1, 3, 5, 10]);
        p.snooze_min = 7;
        assert_eq!(p.next_snooze(), 10);
        p.snooze_min = 200;
        assert_eq!(p.next_snooze(), 1);
    }

    #[test]
    fn template_is_kept_only_when_valid() {
        let mut n = NoticePrefs::default();
        assert!(n.set_template("  Almost at {stop}  ").is_ok());
        assert_eq!(n.template, "Almost at {stop}");
        assert_eq!(n.set_template("   "), Err(TemplateError::Empty));
        assert_eq!(
            n.set_template(&"x".repeat(501)),
            Err(TemplateError::TooLong)
        );
        assert_eq!(n.template, "Almost at {stop}");
    }

    #[test]
    fn notice_when_choices() {
        assert_eq!(NoticeWhen::ALL[0].label(), "When you cross the line");
        assert_eq!(NoticeWhen::ALL[2].label(), "10 min before the circle");
    }

    #[test]
    fn defaults() {
        let p = StopsPrefs::default();
        assert!(p.sound && p.vibrate);
        assert_eq!(p.snooze_min, 5);
        assert!(p.notice.is_none());
    }

    #[test]
    fn empty_json_loads_defaults() {
        let p: StopsPrefs = serde_json::from_str("{}").unwrap();
        assert_eq!(p, StopsPrefs::default());
        let n: NoticePrefs = serde_json::from_str("{}").unwrap();
        assert_eq!(n.template, DEFAULT_TEMPLATE);
    }

    #[test]
    fn partial_json_keeps_the_rest() {
        let p: StopsPrefs =
            serde_json::from_str(r#"{"rule":"Radius1k","vibrate":false,"future":1}"#).unwrap();
        assert!(!p.vibrate && p.sound);
    }

    #[test]
    fn round_trip() {
        let p = StopsPrefs {
            notice: Some(NoticePrefs {
                chat: "123@g.us".into(),
                chat_name: "Family".into(),
                template: "On my way to {stop}".into(),
                when: NoticeWhen::MinutesBefore(10),
            }),
            ..StopsPrefs::default()
        };
        let back: StopsPrefs = serde_json::from_str(&serde_json::to_string(&p).unwrap()).unwrap();
        assert_eq!(back, p);
    }

    #[test]
    fn notice_state_from_prefs() {
        let mut n = NoticePrefs {
            chat: "c".into(),
            ..NoticePrefs::default()
        };
        let s = n.to_notice_state();
        assert_eq!(s.rule, NoticeRule::WithAlarm);
        assert_eq!((s.chat.as_str(), s.sent, s.attempts), ("c", false, 0));
        n.when = NoticeWhen::MinutesBefore(10);
        assert_eq!(
            n.to_notice_state().rule,
            NoticeRule::BeforeCircle {
                before: Duration::from_secs(600)
            }
        );
    }

    #[test]
    fn old_prefs_with_a_rule_and_notice_still_load() {
        let json = r#"{"rule":"Eta2min","sound":false,"notice":{"chat":"c","when":"WithAlarm"}}"#;
        let p: StopsPrefs = serde_json::from_str(json).unwrap();
        assert!(!p.sound);
        assert_eq!(p.notice.unwrap().chat, "c");
    }

    #[test]
    fn tile_url_round_trip_and_fallback() {
        let mut p = StopsPrefs::default();
        assert!(p.tile_source().is_default());
        let m = TileSource::parse("https://maps.maltepe.edu.tr/tiles/osm/{z}/{x}/{y}").unwrap();
        p.set_tile_url(&m);
        let back: StopsPrefs = serde_json::from_str(&serde_json::to_string(&p).unwrap()).unwrap();
        assert_eq!(back.tile_source(), m);
        p.set_tile_url(&TileSource::default_source());
        assert_eq!(p.tile_url, None);
        p.tile_url = Some("nonsense".into());
        assert!(p.tile_source().is_default());
    }

    #[test]
    fn old_prefs_json_has_no_tile_url() {
        let p: StopsPrefs = serde_json::from_str(r#"{"sound":false}"#).unwrap();
        assert_eq!(p.tile_url, None);
    }

    #[test]
    fn tile_cache_default_cycle_and_snap() {
        let mut p = StopsPrefs::default();
        assert_eq!(p.tile_cache_mb, 128);
        assert_eq!(p.tile_cache_bytes(), 128 * 1024 * 1024);
        let mut seen = Vec::new();
        for _ in 0..5 {
            p.tile_cache_mb = p.next_tile_cache();
            seen.push(p.tile_cache_mb);
        }
        assert_eq!(seen, [256, 512, 1024, 128, 256]);
        p.tile_cache_mb = 300;
        assert_eq!(p.next_tile_cache(), 128);
        let old: StopsPrefs = serde_json::from_str(r#"{"sound":false}"#).unwrap();
        assert_eq!(old.tile_cache_mb, 128);
    }

    #[test]
    fn alarm_is_default_tracks_changes() {
        let mut p = StopsPrefs::default();
        assert!(p.alarm_is_default());
        p.snooze_min = 10;
        assert!(!p.alarm_is_default());
    }

    #[test]
    fn with_alarm_defaults_resets_only_alarm_fields() {
        let p = StopsPrefs {
            sound: false,
            vibrate: true,
            snooze_min: 15,
            notice: Some(NoticePrefs::default()),
            tile_url: Some("https://t.example/{z}/{x}/{y}.pbf".into()),
            appearance: Appearance::Dark,
            vibration: VibrationPattern::Long,
            volume_pct: 100,
            alarm_sound: Some(AlarmSound {
                uri: "content://x".into(),
                title: "X".into(),
            }),
            tile_cache_mb: 512,
        };
        let r = p.with_alarm_defaults();
        assert!(r.alarm_is_default());
        assert_eq!(r.appearance, Appearance::Dark);
        assert_eq!(r.tile_cache_mb, 512);
        assert_eq!(r.tile_url, p.tile_url);
        assert_eq!(r.notice, p.notice);
    }
}
