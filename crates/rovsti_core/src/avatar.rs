//! Avatar helpers: the monogram letter and a stable per-contact hue.

/// Number of hue pairs the UI defines (DESIGN.md "Palette for atmosphere").
pub const HUE_PAIRS: usize = 6;

/// What an avatar's hue is derived from.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AvatarKey<'a> {
    /// A saved contact (its id is stable across runs).
    Contact(i64),
    /// A number the owner knows by name from elsewhere: its normalised
    /// digits (E.164 or national) are hashed the same way every run.
    Number(&'a str),
    /// An unknown caller: no hue at all (the UI shows the base pair on grey).
    Unknown,
}

/// The hue pair for an avatar, one of [`HUE_PAIRS`]. Stable across runs (a
/// fixed hash, never the std `RandomState`); `Unknown` gets `None`.
pub fn avatar_hue(key: AvatarKey<'_>) -> Option<usize> {
    match key {
        AvatarKey::Contact(id) => hue_index(Some(id)),
        AvatarKey::Number(digits) if !digits.is_empty() => {
            // FNV-1a, then the same finaliser as contacts.
            let hash = digits.bytes().fold(0xcbf2_9ce4_8422_2325_u64, |h, b| {
                (h ^ u64::from(b)).wrapping_mul(0x0100_0000_01b3)
            });
            hue_index(Some(hash as i64))
        }
        AvatarKey::Number(_) | AvatarKey::Unknown => None,
    }
}

/// A stable hue-pair index for a contact id; `None` (unknown numbers) gets
/// `None` so the UI can use the base pair on grey.
pub fn hue_index(contact_id: Option<i64>) -> Option<usize> {
    contact_id.map(|id| {
        // splitmix64 finaliser: neighbouring ids land on different pairs.
        let mut x = id as u64;
        x = (x ^ (x >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        x = (x ^ (x >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        x ^= x >> 31;
        (x % HUE_PAIRS as u64) as usize
    })
}

/// The first letter of `name`, upper-cased, or `None` for numbers and empty
/// names (the UI then shows the generic person glyph).
pub fn monogram(name: &str) -> Option<char> {
    name.chars()
        .find(|c| c.is_alphabetic())
        .map(|c| c.to_uppercase().next().unwrap_or(c))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn monogram_picks_the_first_letter() {
        assert_eq!(monogram("ada lovelace"), Some('A'));
        assert_eq!(monogram("  élan"), Some('É'));
        assert_eq!(monogram("+90 555 111"), None);
        assert_eq!(monogram(""), None);
        assert_eq!(monogram("5 Alice"), Some('A'));
    }

    #[test]
    fn avatar_hue_maps_contacts_numbers_and_strangers() {
        assert_eq!(avatar_hue(AvatarKey::Unknown), None);
        assert_eq!(avatar_hue(AvatarKey::Number("")), None);
        assert_eq!(avatar_hue(AvatarKey::Contact(7)), hue_index(Some(7)));
        // Same number, same pair, in range; the value is pinned so a change
        // of hash (which would recolour every avatar) fails loudly.
        let a = avatar_hue(AvatarKey::Number("+905551112233")).expect("pair");
        assert_eq!(avatar_hue(AvatarKey::Number("+905551112233")), Some(a));
        assert!(a < HUE_PAIRS);
        assert_eq!(a, 4);
        let seen: std::collections::HashSet<_> = (0..80)
            .filter_map(|n| avatar_hue(AvatarKey::Number(&format!("+90555000{n:04}"))))
            .collect();
        assert_eq!(seen.len(), HUE_PAIRS, "every pair is used");
    }

    #[test]
    fn hue_is_stable_and_in_range() {
        assert_eq!(hue_index(None), None);
        for id in -3..200 {
            let a = hue_index(Some(id)).expect("known");
            assert_eq!(hue_index(Some(id)), Some(a));
            assert!(a < HUE_PAIRS);
        }
        let used: std::collections::HashSet<_> =
            (0..60).filter_map(|id| hue_index(Some(id))).collect();
        assert_eq!(used.len(), HUE_PAIRS, "all pairs get used");
    }
}
