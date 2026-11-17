//! The vault passphrase (plan 3.1, 4): 130 random bits as 26 Crockford base32 characters, plus two check characters.
//!
//! Shown as seven groups of four, `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`; the last two characters are the check.
//!
//! Typo check: the 28 symbols are a codeword of a Reed-Solomon code over GF(32) with two check symbols (generator
//! roots 1 and α, field polynomial x⁵ + x² + 1). Its minimum distance is 3, so **every** error in one or two
//! characters is caught: any wrong character, any two wrong characters, any swap of two different characters. A
//! random larger mistake slips through with probability 1/1024. The check symbols carry no secret: the 130 bits are
//! the 26 data characters.
//!
//! Reading is forgiving: case does not matter, spaces and dashes are ignored, and Crockford's aliases are read as
//! the digit they look like (`I` and `L` as `1`, `O` as `0`). `U` is not in the alphabet.

use std::fmt;
use std::str::FromStr;

use thiserror::Error;
use zeroize::Zeroizing;

use super::Entropy;

/// Crockford's base32 alphabet.
const ALPHABET: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";
/// Random characters: 26 × 5 = 130 bits.
pub const DATA_CHARS: usize = 26;
/// The check characters after them.
pub const CHECK_CHARS: usize = 2;
const TOTAL: usize = DATA_CHARS + CHECK_CHARS;
const GROUP: usize = 4;
/// The bits of entropy in a passphrase.
pub const BITS: usize = DATA_CHARS * 5;
/// The bytes of entropy [`Passphrase::from_entropy`] reads (the last six bits are unused).
pub const ENTROPY_BYTES: usize = BITS.div_ceil(8);
/// Field polynomial x⁵ + x² + 1 (primitive, so α = 2 generates GF(32)).
const FIELD_POLY: u8 = 0b10_0101;

/// Why typed text is not a passphrase. The variants never carry the typed text.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Error)]
pub enum PassphraseError {
    #[error("a passphrase has 28 characters, this has {found}")]
    Length { found: usize },
    #[error("the passphrase has a character that is not used in passphrases")]
    Character,
    #[error("the passphrase has a typo")]
    Typo,
}

/// A vault passphrase. Holds the 28 symbol values (0 to 31), zeroized on drop; `Debug` never shows them.
#[derive(Clone, PartialEq, Eq)]
pub struct Passphrase {
    symbols: Zeroizing<[u8; TOTAL]>,
}

impl fmt::Debug for Passphrase {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Passphrase(…)")
    }
}

impl Passphrase {
    /// A new passphrase from the caller's random bytes.
    pub fn generate(entropy: &mut impl Entropy) -> Passphrase {
        let mut bytes = Zeroizing::new([0u8; ENTROPY_BYTES]);
        entropy.fill(bytes.as_mut_slice());
        Self::from_entropy(&bytes)
    }

    /// The passphrase of the first 130 bits of `bytes`, read most significant bit first.
    pub fn from_entropy(bytes: &[u8; ENTROPY_BYTES]) -> Passphrase {
        let mut symbols = Zeroizing::new([0u8; TOTAL]);
        for (index, symbol) in symbols.iter_mut().take(DATA_CHARS).enumerate() {
            let mut value = 0u8;
            for bit in index * 5..index * 5 + 5 {
                value = (value << 1) | ((bytes[bit / 8] >> (7 - bit % 8)) & 1);
            }
            *symbol = value;
        }
        let (p, q) = check_symbols(&symbols[..DATA_CHARS]);
        symbols[DATA_CHARS] = p;
        symbols[DATA_CHARS + 1] = q;
        Passphrase { symbols }
    }

    /// Reads typed or pasted text (see the module docs for what is tolerated).
    pub fn parse(text: &str) -> Result<Passphrase, PassphraseError> {
        let mut symbols = Zeroizing::new([0u8; TOTAL]);
        let mut found = 0;
        let mut bad_character = false;
        for c in text.chars() {
            if c.is_whitespace() || is_dash(c) {
                continue;
            }
            match symbol_of(c) {
                Some(value) if found < TOTAL => symbols[found] = value,
                Some(_) => {}
                None => bad_character = true,
            }
            found += 1;
        }
        if bad_character {
            return Err(PassphraseError::Character);
        }
        if found != TOTAL {
            return Err(PassphraseError::Length { found });
        }
        if syndromes(&symbols[..]) != (0, 0) {
            return Err(PassphraseError::Typo);
        }
        Ok(Passphrase { symbols })
    }

    /// The display form: seven groups of four joined by `-`.
    pub fn grouped(&self) -> Zeroizing<String> {
        let mut text = Zeroizing::new(String::with_capacity(TOTAL + TOTAL / GROUP));
        for (index, &symbol) in self.symbols.iter().enumerate() {
            if index > 0 && index % GROUP == 0 {
                text.push('-');
            }
            text.push(char::from(ALPHABET[usize::from(symbol)]));
        }
        text
    }

    /// What the KDF reads: the 26 data characters, upper case, without separators or check.
    pub(super) fn kdf_input(&self) -> Zeroizing<[u8; DATA_CHARS]> {
        let mut out = Zeroizing::new([0u8; DATA_CHARS]);
        for (byte, &symbol) in out.iter_mut().zip(self.symbols.iter()) {
            *byte = ALPHABET[usize::from(symbol)];
        }
        out
    }
}

impl FromStr for Passphrase {
    type Err = PassphraseError;

    fn from_str(text: &str) -> Result<Self, Self::Err> {
        Self::parse(text)
    }
}

fn is_dash(c: char) -> bool {
    matches!(
        c,
        '-' | '\u{2010}' | '\u{2011}' | '\u{2012}' | '\u{2013}' | '\u{2014}' | '\u{2212}'
    )
}

fn symbol_of(c: char) -> Option<u8> {
    let upper = match c.to_ascii_uppercase() {
        'I' | 'L' => '1',
        'O' => '0',
        other => other,
    };
    let byte = u8::try_from(upper).ok()?;
    ALPHABET
        .iter()
        .position(|&a| a == byte)
        .and_then(|i| u8::try_from(i).ok())
}

const fn gf_mul(mut a: u8, mut b: u8) -> u8 {
    let mut product = 0;
    while b != 0 {
        if b & 1 != 0 {
            product ^= a;
        }
        b >>= 1;
        a <<= 1;
        if a & 0b10_0000 != 0 {
            a ^= FIELD_POLY;
        }
    }
    product
}

/// α^i.
fn alpha_pow(i: usize) -> u8 {
    (0..i % 31).fold(1, |x, _| gf_mul(x, 2))
}

/// The inverse of a non-zero element (a^30, since a^31 = 1).
fn gf_inv(a: u8) -> u8 {
    (0..30).fold(1, |x, _| gf_mul(x, a))
}

/// `(Σ cᵢ, Σ cᵢ·αⁱ)`: both zero for a codeword.
fn syndromes(symbols: &[u8]) -> (u8, u8) {
    symbols
        .iter()
        .enumerate()
        .fold((0, 0), |(s0, s1), (i, &c)| {
            (s0 ^ c, s1 ^ gf_mul(c, alpha_pow(i)))
        })
}

/// The two check symbols (positions 26 and 27) that make `data` a codeword: with D₀, D₁ the syndromes of the data,
/// `p + q = D₀` and `α²⁶p + α²⁷q = D₁`.
fn check_symbols(data: &[u8]) -> (u8, u8) {
    let (d0, d1) = syndromes(data);
    let a26 = alpha_pow(DATA_CHARS);
    let a27 = alpha_pow(DATA_CHARS + 1);
    let p = gf_mul(d1 ^ gf_mul(a27, d0), gf_inv(a26 ^ a27));
    (p, d0 ^ p)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Passphrase {
        Passphrase::from_entropy(&[
            0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xaa, 0xbb, 0xcc, 0xdd,
            0xee, 0xff, 0x5a,
        ])
    }

    #[test]
    fn a_fixed_entropy_gives_a_fixed_passphrase() {
        // 00 11 22 33 44 ... read five bits at a time: 00000 00000 01000 10010 00100 ... = 0 0 8 J 4 ...; "04" is
        // the check.
        assert_eq!(
            sample().grouped().as_str(),
            "008J-4CT4-ANK7-F24S-NAXW-SQFE-ZX04"
        );
    }

    #[test]
    fn grouped_text_parses_back() {
        let pass = sample();
        let text = pass.grouped();
        assert_eq!(text.len(), 34);
        assert_eq!(Passphrase::parse(&text), Ok(pass.clone()));
        assert_eq!(text.parse::<Passphrase>(), Ok(pass));
    }

    #[test]
    fn parsing_tolerates_case_spaces_dashes_and_aliases() {
        let pass = sample();
        let text = pass.grouped().to_string();
        let sloppy = text.to_lowercase().replace('-', " ").replace('1', "l");
        assert_eq!(Passphrase::parse(&sloppy), Ok(pass.clone()));
        let joined = text.replace('-', "").replace('0', "o");
        assert_eq!(Passphrase::parse(&joined), Ok(pass.clone()));
        let dashed = text.replace('-', "\u{2013}");
        assert_eq!(Passphrase::parse(&format!("  {dashed}\n")), Ok(pass));
    }

    #[test]
    fn length_and_alphabet_errors() {
        assert_eq!(
            Passphrase::parse("ABCD"),
            Err(PassphraseError::Length { found: 4 })
        );
        assert_eq!(
            Passphrase::parse(""),
            Err(PassphraseError::Length { found: 0 })
        );
        let text = sample().grouped().to_string();
        assert_eq!(
            Passphrase::parse(&format!("{text}A")),
            Err(PassphraseError::Length { found: 29 })
        );
        let with_u = format!("U{}", &text[1..]);
        assert_eq!(Passphrase::parse(&with_u), Err(PassphraseError::Character));
        assert_eq!(
            Passphrase::parse(&text.replace('-', "_")),
            Err(PassphraseError::Character)
        );
    }

    #[test]
    fn every_single_wrong_character_is_a_typo() {
        let symbols: Vec<u8> = sample().grouped().bytes().filter(|&b| b != b'-').collect();
        for position in 0..TOTAL {
            for &replacement in ALPHABET {
                if replacement == symbols[position] {
                    continue;
                }
                let mut typo = symbols.clone();
                typo[position] = replacement;
                let text = String::from_utf8(typo).unwrap_or_default();
                assert_eq!(
                    Passphrase::parse(&text),
                    Err(PassphraseError::Typo),
                    "{position}"
                );
            }
        }
    }

    #[test]
    fn every_swap_of_two_different_characters_is_a_typo() {
        let symbols: Vec<u8> = sample().grouped().bytes().filter(|&b| b != b'-').collect();
        for i in 0..TOTAL {
            for j in i + 1..TOTAL {
                if symbols[i] == symbols[j] {
                    continue;
                }
                let mut swapped = symbols.clone();
                swapped.swap(i, j);
                let text = String::from_utf8(swapped).unwrap_or_default();
                assert_eq!(
                    Passphrase::parse(&text),
                    Err(PassphraseError::Typo),
                    "{i} {j}"
                );
            }
        }
    }

    #[test]
    fn entropy_reaches_all_130_bits() {
        // Flipping any one of the 130 bits changes the data characters; the six bits after them are unused.
        let base = [0u8; ENTROPY_BYTES];
        let zero = Passphrase::from_entropy(&base).kdf_input();
        for bit in 0..ENTROPY_BYTES * 8 {
            let mut bytes = base;
            bytes[bit / 8] ^= 0x80 >> (bit % 8);
            let changed = *Passphrase::from_entropy(&bytes).kdf_input() != *zero;
            assert_eq!(changed, bit < BITS, "bit {bit}");
        }
    }

    #[test]
    fn debug_hides_the_passphrase() {
        let pass = sample();
        assert_eq!(format!("{pass:?}"), "Passphrase(…)");
    }

    #[test]
    fn the_field_is_gf32() {
        // α generates all 31 non-zero elements.
        let mut seen: Vec<u8> = (0..31).map(alpha_pow).collect();
        seen.sort_unstable();
        seen.dedup();
        assert_eq!(seen.len(), 31);
        assert!(!seen.contains(&0));
        for a in 1..32 {
            assert_eq!(gf_mul(a, gf_inv(a)), 1, "{a}");
        }
    }
}
