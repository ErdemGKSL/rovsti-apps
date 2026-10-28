//! A fixed-capacity, stack-allocated ASCII string for hot-path formatting.
//!
//! The timer, badge and Recents time labels are produced every frame or every
//! second, so they are rendered into this buffer instead of a fresh `String`.

use std::fmt;

/// An inline string of at most `N` bytes. Writes that would overflow are
/// truncated at a character boundary, never panic.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct SmallText<const N: usize> {
    buf: [u8; N],
    len: u8,
}

impl<const N: usize> SmallText<N> {
    pub const fn new() -> Self {
        assert!(N <= u8::MAX as usize);
        Self {
            buf: [0; N],
            len: 0,
        }
    }

    pub fn as_str(&self) -> &str {
        // The buffer only ever receives whole `&str` pieces (see `push_str`),
        // so it is valid UTF-8; fall back to empty rather than panic.
        std::str::from_utf8(&self.buf[..usize::from(self.len)]).unwrap_or("")
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    pub fn len(&self) -> usize {
        usize::from(self.len)
    }

    /// Appends `s`, dropping it entirely when it does not fit so the
    /// content stays valid UTF-8.
    pub fn push_str(&mut self, s: &str) {
        let start = usize::from(self.len);
        let end = start + s.len();
        if end > N {
            return;
        }
        self.buf[start..end].copy_from_slice(s.as_bytes());
        // `end <= N <= u8::MAX` is guaranteed by the checks above.
        self.len = end as u8;
    }

    /// Appends `value` in decimal, left-padded with zeros to `width` digits.
    pub fn push_padded(&mut self, value: u64, width: usize) {
        let mut digits = [0u8; 20];
        let mut i = digits.len();
        let mut v = value;
        loop {
            i -= 1;
            digits[i] = b'0' + (v % 10) as u8;
            v /= 10;
            if v == 0 {
                break;
            }
        }
        let used = digits.len() - i;
        for _ in used..width {
            self.push_str("0");
        }
        if let Ok(s) = std::str::from_utf8(&digits[i..]) {
            self.push_str(s);
        }
    }
}

impl<const N: usize> Default for SmallText<N> {
    fn default() -> Self {
        Self::new()
    }
}

impl<const N: usize> fmt::Display for SmallText<N> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

impl<const N: usize> fmt::Debug for SmallText<N> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        fmt::Debug::fmt(self.as_str(), f)
    }
}

impl<const N: usize> AsRef<str> for SmallText<N> {
    fn as_ref(&self) -> &str {
        self.as_str()
    }
}

impl<const N: usize> PartialEq<str> for SmallText<N> {
    fn eq(&self, other: &str) -> bool {
        self.as_str() == other
    }
}

impl<const N: usize> PartialEq<&str> for SmallText<N> {
    fn eq(&self, other: &&str) -> bool {
        self.as_str() == *other
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pushes_and_pads() {
        let mut t = SmallText::<8>::new();
        t.push_padded(5, 2);
        t.push_str(":");
        t.push_padded(0, 2);
        assert_eq!(t, "05:00");
    }

    #[test]
    fn overflowing_piece_is_dropped_whole() {
        let mut t = SmallText::<4>::new();
        t.push_str("abc");
        t.push_str("de");
        assert_eq!(t.as_str(), "abc");
        assert_eq!(t.len(), 3);
    }

    #[test]
    fn zero_renders_one_digit() {
        let mut t = SmallText::<4>::new();
        t.push_padded(0, 1);
        assert_eq!(t, "0");
    }

    #[test]
    fn default_is_empty() {
        assert!(SmallText::<4>::default().is_empty());
    }
}
