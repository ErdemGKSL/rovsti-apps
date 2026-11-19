//! Byte offsets of a `&str` to and from UTF-16 code units.

/// The UTF-16 length of `text[..byte]`. `byte` must be a char boundary.
pub(super) fn units_before(text: &str, byte: usize) -> usize {
    text[..byte].encode_utf16().count()
}

/// The byte offset of UTF-16 offset `units` in `text`; `None` when it falls inside a character or past the end.
pub(super) fn byte_of(text: &str, units: usize) -> Option<usize> {
    let mut seen = 0usize;
    for (byte, ch) in text.char_indices() {
        if seen == units {
            return Some(byte);
        }
        if seen > units {
            return None;
        }
        seen += ch.len_utf16();
    }
    (seen == units).then_some(text.len())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_surrogate_pairs_as_two() {
        let text = "a😀b";
        assert_eq!(units_before(text, 1), 1);
        assert_eq!(units_before(text, 5), 3);
        assert_eq!(byte_of(text, 3), Some(5));
        assert_eq!(byte_of(text, 2), None);
        assert_eq!(byte_of(text, 4), Some(6));
        assert_eq!(byte_of(text, 5), None);
    }
}
