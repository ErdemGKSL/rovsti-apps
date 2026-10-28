//! SIM accounts (AD-26, stories 16.3 to 16.5).
//!
//! Rust picks the account; Kotlin only lists and places. A phone with fewer
//! than two SIM-backed accounts shows no SIM element anywhere
//! ([`SimInfo::visible`]), so a single-SIM phone is untouched.
//!
//! Everything here is pure and built once per `phone_accounts` pull, never per
//! frame. The call-log lookup is a small sorted vector (two or three entries).

use std::sync::Arc;

use crate::wire::PhoneAccounts;

/// The longest chip text before it is cut with an ellipsis.
pub const CHIP_MAX_CHARS: usize = 8;

/// Contrast the chip dot keeps against the chip fill (WCAG non-text).
pub const DOT_MIN_CONTRAST: f32 = 3.0;

/// One call-capable SIM, with the strings the views draw already built.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SimAccount {
    /// Opaque key (AD-26); also what the call-log row carries.
    pub key: Arc<str>,
    /// The carrier or account label as the system names it.
    pub label: Arc<str>,
    /// Chip text: "SIM 1" or the carrier label, at most [`CHIP_MAX_CHARS`].
    pub chip: Arc<str>,
    /// Chooser row title: "SIM 1" (or the label for a slot-less account).
    pub title: Arc<str>,
    /// Slot colour, `0xAARRGGBB`.
    pub color_argb: i32,
    pub slot: Option<u8>,
    pub enabled: bool,
}

/// What the app knows about the phone's SIMs. Cheap to clone (one `Arc`).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SimInfo {
    inner: Arc<Inner>,
}

#[derive(Debug, Default, PartialEq, Eq)]
struct Inner {
    accounts: Vec<SimAccount>,
    default_key: Option<Arc<str>>,
    ask: bool,
}

/// Cuts `text` to `max` characters, ending in an ellipsis when it was longer.
pub fn shorten(text: &str, max: usize) -> String {
    let text = text.trim();
    if text.chars().count() <= max {
        return text.to_owned();
    }
    let mut out: String = text.chars().take(max.saturating_sub(1)).collect();
    out.push('\u{2026}');
    out
}

impl SimInfo {
    /// Builds the lookup from a `phone_accounts` payload. Accounts that are
    /// not SIM-backed (VoIP) are left out: they never get a chip.
    pub fn from_payload(payload: &PhoneAccounts) -> Self {
        let sims: Vec<_> = payload.accounts.iter().filter(|a| a.sim).collect();
        // Two SIMs of the same carrier would read identically: name them by slot.
        let labels_clash = {
            let mut seen: Vec<&str> = Vec::with_capacity(sims.len());
            sims.iter().any(|a| {
                let label = a.label.trim();
                let dup = label.is_empty() || seen.contains(&label);
                seen.push(label);
                dup
            })
        };
        let accounts: Vec<SimAccount> = sims
            .iter()
            .enumerate()
            .map(|(i, a)| {
                let slot = a.slot_index;
                let n = slot.map_or(i + 1, |s| usize::from(s) + 1);
                let numbered = format!("SIM {n}");
                let chip = if labels_clash {
                    numbered.clone()
                } else {
                    shorten(&a.label, CHIP_MAX_CHARS)
                };
                SimAccount {
                    key: a.key.as_str().into(),
                    label: a.label.trim().into(),
                    chip: chip.into(),
                    title: numbered.into(),
                    color_argb: a.color_argb,
                    slot,
                    enabled: a.enabled,
                }
            })
            .collect();
        let default_key = payload
            .default_key
            .as_deref()
            .filter(|k| accounts.iter().any(|a| &*a.key == *k))
            .map(Arc::from);
        let ask = payload.ask && default_key.is_none() && accounts.len() >= 2;
        Self {
            inner: Arc::new(Inner {
                accounts,
                default_key,
                ask,
            }),
        }
    }

    /// Two or more SIMs: every SIM element (chooser, chips, Settings section)
    /// is gated by this.
    pub fn visible(&self) -> bool {
        self.inner.accounts.len() >= 2
    }

    pub fn accounts(&self) -> &[SimAccount] {
        &self.inner.accounts
    }

    pub fn default_key(&self) -> Option<&str> {
        self.inner.default_key.as_deref()
    }

    /// The system is set to "ask every time" and there are two SIMs: Call
    /// opens the chooser first.
    pub fn ask(&self) -> bool {
        self.visible() && self.inner.ask
    }

    /// The account for a call-log or call `account_key`; `None` for an
    /// unknown key (the chip slot then stays empty) or with one SIM.
    pub fn find(&self, key: &str) -> Option<&SimAccount> {
        if !self.visible() {
            return None;
        }
        self.inner.accounts.iter().find(|a| &*a.key == key)
    }

    /// Position of the account `key` names, for the Recents rows' one-time
    /// lookup; `None` with one SIM or an unknown key.
    pub fn index_of(&self, key: &str) -> Option<usize> {
        if !self.visible() {
            return None;
        }
        self.inner.accounts.iter().position(|a| &*a.key == key)
    }

    /// "Ask every time" or the default SIM's title (Settings value).
    pub fn default_label(&self) -> &str {
        match self.default_key().and_then(|k| self.find(k)) {
            Some(account) => &account.title,
            None => "Ask every time",
        }
    }
}

// ---- the chip dot's contrast -------------------------------------------------

fn channel(c: f32) -> f32 {
    if c <= 0.039_28 {
        c / 12.92
    } else {
        ((c + 0.055) / 1.055).powf(2.4)
    }
}

/// WCAG relative luminance of an sRGB colour (0 to 1 per channel).
pub fn luminance(rgb: [f32; 3]) -> f32 {
    0.2126 * channel(rgb[0]) + 0.7152 * channel(rgb[1]) + 0.0722 * channel(rgb[2])
}

/// WCAG contrast ratio of two colours (1 to 21).
pub fn contrast(a: [f32; 3], b: [f32; 3]) -> f32 {
    let (la, lb) = (luminance(a), luminance(b));
    let (hi, lo) = if la > lb { (la, lb) } else { (lb, la) };
    (hi + 0.05) / (lo + 0.05)
}

/// `0xAARRGGBB` to 0 to 1 RGB (alpha dropped).
pub fn argb_rgb(argb: i32) -> [f32; 3] {
    let v = argb as u32;
    [
        ((v >> 16) & 0xFF) as f32 / 255.0,
        ((v >> 8) & 0xFF) as f32 / 255.0,
        (v & 0xFF) as f32 / 255.0,
    ]
}

/// The slot colour, pushed towards black or white (whichever helps) until it
/// keeps at least `min` contrast against `fill`. A colour that already does is
/// returned unchanged. At most 12 steps.
pub fn clamp_dot(color: [f32; 3], fill: [f32; 3], min: f32) -> [f32; 3] {
    if contrast(color, fill) >= min {
        return color;
    }
    let target = if luminance(fill) > 0.5 {
        [0.0; 3]
    } else {
        [1.0; 3]
    };
    let mut out = color;
    for step in 1..=12 {
        let t = step as f32 / 12.0;
        out = [
            color[0] + (target[0] - color[0]) * t,
            color[1] + (target[1] - color[1]) * t,
            color[2] + (target[2] - color[2]) * t,
        ];
        if contrast(out, fill) >= min {
            break;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wire::PhoneAccountInfo;

    fn account(key: &str, label: &str, slot: u8, color: i32) -> PhoneAccountInfo {
        PhoneAccountInfo {
            key: key.into(),
            label: label.into(),
            slot_index: Some(slot),
            color_argb: color,
            sim: true,
            enabled: true,
        }
    }

    fn two() -> PhoneAccounts {
        PhoneAccounts {
            accounts: vec![
                account("a#1", "Turkcell", 0, 0xFF1E88E5_u32 as i32),
                account("a#2", "Vodafone Turkey", 1, 0xFFE53935_u32 as i32),
            ],
            default_key: None,
            ask: true,
        }
    }

    #[test]
    fn one_sim_shows_nothing() {
        let info = SimInfo::from_payload(&PhoneAccounts {
            accounts: vec![account("a#1", "Turkcell", 0, 0)],
            default_key: Some("a#1".into()),
            ask: false,
        });
        assert!(!info.visible());
        assert!(!info.ask());
        assert!(info.find("a#1").is_none());
        assert_eq!(info.default_label(), "Ask every time");
    }

    #[test]
    fn no_accounts_shows_nothing() {
        let info = SimInfo::from_payload(&PhoneAccounts::default());
        assert!(!info.visible());
        assert!(info.accounts().is_empty());
    }

    #[test]
    fn two_sims_ask_every_time() {
        let info = SimInfo::from_payload(&two());
        assert!(info.visible());
        assert!(info.ask());
        assert_eq!(info.accounts().len(), 2);
        assert_eq!(&*info.find("a#2").expect("present").title, "SIM 2");
        // Long carrier names are cut with an ellipsis.
        assert_eq!(&*info.find("a#2").expect("present").chip, "Vodafon\u{2026}");
        assert_eq!(&*info.find("a#1").expect("present").chip, "Turkcell");
        assert_eq!(info.default_label(), "Ask every time");
    }

    #[test]
    fn a_missing_default_means_ask_not_a_guess() {
        let mut payload = two();
        payload.default_key = Some("gone#9".into());
        payload.ask = false;
        let info = SimInfo::from_payload(&payload);
        assert_eq!(info.default_key(), None);
    }

    #[test]
    fn a_default_is_named_and_not_asked() {
        let mut payload = two();
        payload.default_key = Some("a#2".into());
        payload.ask = false;
        let info = SimInfo::from_payload(&payload);
        assert!(!info.ask());
        assert_eq!(info.default_label(), "SIM 2");
    }

    #[test]
    fn the_same_carrier_twice_is_named_by_slot() {
        let info = SimInfo::from_payload(&PhoneAccounts {
            accounts: vec![
                account("a#1", "Turkcell", 0, 0),
                account("a#2", "Turkcell", 1, 0),
            ],
            default_key: None,
            ask: true,
        });
        assert_eq!(&*info.accounts()[0].chip, "SIM 1");
        assert_eq!(&*info.accounts()[1].chip, "SIM 2");
    }

    #[test]
    fn an_unknown_log_key_finds_nothing() {
        let info = SimInfo::from_payload(&two());
        assert!(info.find("other#1").is_none());
    }

    #[test]
    fn voip_accounts_are_not_sims() {
        let mut payload = two();
        payload.accounts.push(PhoneAccountInfo {
            sim: false,
            ..account("sip#1", "Office", 0, 0)
        });
        let info = SimInfo::from_payload(&payload);
        assert_eq!(info.accounts().len(), 2);
    }

    #[test]
    fn shorten_cuts_at_the_limit() {
        assert_eq!(shorten("Turkcell", 8), "Turkcell");
        assert_eq!(shorten("Turkcell GSM", 8), "Turkcel\u{2026}");
        assert_eq!(shorten("  SIM 1 ", 8), "SIM 1");
    }

    #[test]
    fn a_low_contrast_dot_is_pushed_until_it_reads() {
        let white = [1.0, 1.0, 1.0];
        let pale = [0.9, 0.9, 0.5];
        assert!(contrast(pale, white) < DOT_MIN_CONTRAST);
        let fixed = clamp_dot(pale, white, DOT_MIN_CONTRAST);
        assert!(contrast(fixed, white) >= DOT_MIN_CONTRAST);
        // On a dark fill the same colour already reads and is left alone.
        let black = [0.0; 3];
        assert_eq!(clamp_dot(pale, black, DOT_MIN_CONTRAST), pale);
    }

    #[test]
    fn a_dark_dot_on_a_dark_fill_is_lightened() {
        let fill = [0.1, 0.1, 0.1];
        let navy = [0.0, 0.0, 0.3];
        let fixed = clamp_dot(navy, fill, DOT_MIN_CONTRAST);
        assert!(contrast(fixed, fill) >= DOT_MIN_CONTRAST);
        assert!(luminance(fixed) > luminance(navy));
    }

    #[test]
    fn argb_unpacks_channels() {
        let rgb = argb_rgb(0xFF336699_u32 as i32);
        assert!((rgb[0] - 0.2).abs() < 0.01 && (rgb[2] - 0.6).abs() < 0.01);
    }
}
