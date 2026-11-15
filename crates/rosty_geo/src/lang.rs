//! The language of the sentences this crate writes for people.
//!
//! Every text producer has an `_in` form that takes a [`Lang`]; the form without it writes English.
//! Turkish sentences are built so that no suffix has to follow a name the user typed (a stop's name
//! takes no case ending: "Hedef: Kadıköy", never "Kadıköy'e").

use serde::{Deserialize, Deserializer, Serialize, Serializer};

/// A language the texts exist in. Anything unknown reads as English.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum Lang {
    #[default]
    En,
    Tr,
}

impl Lang {
    pub const ALL: [Lang; 2] = [Lang::En, Lang::Tr];

    /// From a locale tag as phones write it: `tr`, `tr-TR`, `tr_TR`, `TR`. Unknown tags are English.
    pub fn from_tag(tag: &str) -> Self {
        let language = tag
            .trim()
            .split(['-', '_'])
            .next()
            .unwrap_or_default()
            .to_ascii_lowercase();
        match language.as_str() {
            "tr" | "tur" => Self::Tr,
            _ => Self::En,
        }
    }

    /// Whether this is the default language (left out of stored files).
    pub fn is_default(&self) -> bool {
        *self == Self::default()
    }

    /// The two-letter tag.
    pub fn tag(self) -> &'static str {
        match self {
            Self::En => "en",
            Self::Tr => "tr",
        }
    }

    /// Picks the text of this language.
    pub fn pick<'a>(self, en: &'a str, tr: &'a str) -> &'a str {
        match self {
            Self::En => en,
            Self::Tr => tr,
        }
    }

    /// Short month name for 1..=12 (clamped).
    pub fn month(self, month: u8) -> &'static str {
        const EN: [&str; 12] = [
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        ];
        const TR: [&str; 12] = [
            "Oca", "Şub", "Mar", "Nis", "May", "Haz", "Tem", "Ağu", "Eyl", "Eki", "Kas", "Ara",
        ];
        let index = usize::from(month.clamp(1, 12) - 1);
        match self {
            Self::En => EN[index],
            Self::Tr => TR[index],
        }
    }

    /// `value` with one decimal and this language's decimal mark: "1.2" / "1,2".
    pub fn one_decimal(self, value: f64) -> String {
        let text = format!("{value:.1}");
        match self {
            Self::En => text,
            Self::Tr => text.replace('.', ","),
        }
    }

    /// `1234567` in groups of three: "1 234 567" / "1.234.567".
    pub fn grouped(self, n: usize) -> String {
        let separator = match self {
            Self::En => ' ',
            Self::Tr => '.',
        };
        let digits = n.to_string();
        let mut out = String::with_capacity(digits.len() + digits.len() / 3);
        for (i, c) in digits.chars().enumerate() {
            if i > 0 && (digits.len() - i).is_multiple_of(3) {
                out.push(separator);
            }
            out.push(c);
        }
        out
    }

    /// A count with its noun: English has a plural, Turkish keeps the noun singular after a number.
    pub(crate) fn count(self, n: usize, one: &str, many: &str, tr: &str) -> String {
        match self {
            Self::En => format!("{n} {}", if n == 1 { one } else { many }),
            Self::Tr => format!("{n} {tr}"),
        }
    }
}

impl Serialize for Lang {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        serializer.serialize_str(self.tag())
    }
}

/// Lenient: any locale tag, `null` or something that is not a string at all reads as a language.
impl<'de> Deserialize<'de> for Lang {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let value = serde_json::Value::deserialize(deserializer)?;
        Ok(value.as_str().map(Self::from_tag).unwrap_or_default())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn tags_as_phones_write_them() {
        for tag in ["tr", "TR", "tr-TR", "tr_TR", " tr-CY ", "tur", "tr-Latn-TR"] {
            assert_eq!(Lang::from_tag(tag), Lang::Tr, "{tag}");
        }
        for tag in ["en", "en-GB", "", "de-DE", "trk", "t", "-tr", "az-TR", "xx"] {
            assert_eq!(Lang::from_tag(tag), Lang::En, "{tag}");
        }
        assert_eq!(Lang::default(), Lang::En);
        assert_eq!(Lang::Tr.tag(), "tr");
    }

    #[test]
    fn json_is_lenient_and_writes_the_short_tag() {
        #[derive(Deserialize, Serialize)]
        struct Req {
            #[serde(default)]
            locale: Lang,
        }
        let lang = |json: &str| serde_json::from_str::<Req>(json).unwrap().locale;
        assert_eq!(lang("{}"), Lang::En);
        assert_eq!(lang(r#"{"locale": "tr-TR"}"#), Lang::Tr);
        assert_eq!(lang(r#"{"locale": null}"#), Lang::En);
        assert_eq!(lang(r#"{"locale": 7}"#), Lang::En);
        assert_eq!(lang(r#"{"locale": "pt-BR"}"#), Lang::En);
        let written = serde_json::to_string(&Req { locale: Lang::Tr }).unwrap();
        assert_eq!(written, r#"{"locale":"tr"}"#);
    }

    #[test]
    fn numbers_and_months() {
        assert_eq!(Lang::En.one_decimal(1.25), "1.2");
        assert_eq!(Lang::Tr.one_decimal(1.25), "1,2");
        assert_eq!(Lang::Tr.one_decimal(12.0), "12,0");
        assert_eq!(Lang::En.grouped(1_234_567), "1 234 567");
        assert_eq!(Lang::Tr.grouped(1_234_567), "1.234.567");
        assert_eq!(Lang::Tr.grouped(999), "999");
        assert_eq!(Lang::Tr.grouped(0), "0");
        assert_eq!(Lang::En.month(11), "Nov");
        assert_eq!(Lang::Tr.month(11), "Kas");
        assert_eq!(Lang::Tr.month(0), "Oca");
        assert_eq!(Lang::Tr.month(99), "Ara");
        assert_eq!(Lang::Tr.month(2), "Şub");
        assert_eq!(Lang::En.count(1, "stop", "stops", "durak"), "1 stop");
        assert_eq!(Lang::En.count(0, "stop", "stops", "durak"), "0 stops");
        assert_eq!(Lang::Tr.count(1, "stop", "stops", "durak"), "1 durak");
        assert_eq!(Lang::Tr.count(12, "stop", "stops", "durak"), "12 durak");
    }
}
