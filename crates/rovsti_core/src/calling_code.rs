//! Country calling codes (ITU) for labelling numbers from abroad.
//!
//! Pure lookup, no data files. Only international numbers get a country; a
//! short code, a sender id or a national number never does.

use crate::PhoneNumber;

/// A country (or shared zone) behind a calling code.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Country {
    pub cc: &'static str,
    pub name: &'static str,
}

/// Calling code to English short name, sorted by code as text.
pub const COUNTRIES: &[(&str, &str)] = &[
    ("1", "US / Canada"),
    ("20", "Egypt"),
    ("211", "South Sudan"),
    ("212", "Morocco"),
    ("213", "Algeria"),
    ("216", "Tunisia"),
    ("218", "Libya"),
    ("220", "Gambia"),
    ("221", "Senegal"),
    ("222", "Mauritania"),
    ("223", "Mali"),
    ("224", "Guinea"),
    ("225", "Ivory Coast"),
    ("226", "Burkina Faso"),
    ("227", "Niger"),
    ("228", "Togo"),
    ("229", "Benin"),
    ("230", "Mauritius"),
    ("231", "Liberia"),
    ("232", "Sierra Leone"),
    ("233", "Ghana"),
    ("234", "Nigeria"),
    ("235", "Chad"),
    ("236", "Central African Republic"),
    ("237", "Cameroon"),
    ("238", "Cape Verde"),
    ("239", "Sao Tome and Principe"),
    ("240", "Equatorial Guinea"),
    ("241", "Gabon"),
    ("242", "Congo"),
    ("243", "DR Congo"),
    ("244", "Angola"),
    ("245", "Guinea-Bissau"),
    ("248", "Seychelles"),
    ("249", "Sudan"),
    ("250", "Rwanda"),
    ("251", "Ethiopia"),
    ("252", "Somalia"),
    ("253", "Djibouti"),
    ("254", "Kenya"),
    ("255", "Tanzania"),
    ("256", "Uganda"),
    ("257", "Burundi"),
    ("258", "Mozambique"),
    ("260", "Zambia"),
    ("261", "Madagascar"),
    ("262", "Reunion"),
    ("263", "Zimbabwe"),
    ("264", "Namibia"),
    ("265", "Malawi"),
    ("266", "Lesotho"),
    ("267", "Botswana"),
    ("268", "Eswatini"),
    ("269", "Comoros"),
    ("27", "South Africa"),
    ("30", "Greece"),
    ("31", "Netherlands"),
    ("32", "Belgium"),
    ("33", "France"),
    ("34", "Spain"),
    ("350", "Gibraltar"),
    ("351", "Portugal"),
    ("352", "Luxembourg"),
    ("353", "Ireland"),
    ("354", "Iceland"),
    ("355", "Albania"),
    ("356", "Malta"),
    ("357", "Cyprus"),
    ("358", "Finland"),
    ("359", "Bulgaria"),
    ("36", "Hungary"),
    ("370", "Lithuania"),
    ("371", "Latvia"),
    ("372", "Estonia"),
    ("373", "Moldova"),
    ("374", "Armenia"),
    ("375", "Belarus"),
    ("376", "Andorra"),
    ("377", "Monaco"),
    ("378", "San Marino"),
    ("380", "Ukraine"),
    ("381", "Serbia"),
    ("382", "Montenegro"),
    ("383", "Kosovo"),
    ("385", "Croatia"),
    ("386", "Slovenia"),
    ("387", "Bosnia and Herzegovina"),
    ("389", "North Macedonia"),
    ("39", "Italy"),
    ("40", "Romania"),
    ("41", "Switzerland"),
    ("420", "Czechia"),
    ("421", "Slovakia"),
    ("423", "Liechtenstein"),
    ("43", "Austria"),
    ("44", "United Kingdom"),
    ("45", "Denmark"),
    ("46", "Sweden"),
    ("47", "Norway"),
    ("48", "Poland"),
    ("49", "Germany"),
    ("502", "Guatemala"),
    ("503", "El Salvador"),
    ("504", "Honduras"),
    ("505", "Nicaragua"),
    ("506", "Costa Rica"),
    ("507", "Panama"),
    ("509", "Haiti"),
    ("51", "Peru"),
    ("52", "Mexico"),
    ("53", "Cuba"),
    ("54", "Argentina"),
    ("55", "Brazil"),
    ("56", "Chile"),
    ("57", "Colombia"),
    ("58", "Venezuela"),
    ("591", "Bolivia"),
    ("592", "Guyana"),
    ("593", "Ecuador"),
    ("595", "Paraguay"),
    ("597", "Suriname"),
    ("598", "Uruguay"),
    ("60", "Malaysia"),
    ("61", "Australia"),
    ("62", "Indonesia"),
    ("63", "Philippines"),
    ("64", "New Zealand"),
    ("65", "Singapore"),
    ("66", "Thailand"),
    ("670", "Timor-Leste"),
    ("673", "Brunei"),
    ("675", "Papua New Guinea"),
    ("676", "Tonga"),
    ("677", "Solomon Islands"),
    ("678", "Vanuatu"),
    ("679", "Fiji"),
    ("685", "Samoa"),
    ("7", "Russia / Kazakhstan"),
    ("81", "Japan"),
    ("82", "South Korea"),
    ("84", "Vietnam"),
    ("852", "Hong Kong"),
    ("853", "Macau"),
    ("855", "Cambodia"),
    ("856", "Laos"),
    ("86", "China"),
    ("880", "Bangladesh"),
    ("886", "Taiwan"),
    ("90", "Turkey"),
    ("91", "India"),
    ("92", "Pakistan"),
    ("93", "Afghanistan"),
    ("94", "Sri Lanka"),
    ("95", "Myanmar"),
    ("960", "Maldives"),
    ("961", "Lebanon"),
    ("962", "Jordan"),
    ("963", "Syria"),
    ("964", "Iraq"),
    ("965", "Kuwait"),
    ("966", "Saudi Arabia"),
    ("967", "Yemen"),
    ("968", "Oman"),
    ("970", "Palestine"),
    ("971", "United Arab Emirates"),
    ("972", "Israel"),
    ("973", "Bahrain"),
    ("974", "Qatar"),
    ("975", "Bhutan"),
    ("976", "Mongolia"),
    ("977", "Nepal"),
    ("98", "Iran"),
    ("992", "Tajikistan"),
    ("993", "Turkmenistan"),
    ("994", "Azerbaijan"),
    ("995", "Georgia"),
    ("996", "Kyrgyzstan"),
    ("998", "Uzbekistan"),
];

/// Exact lookup of a calling code without the `+`.
pub fn country_of_cc(cc: &str) -> Option<Country> {
    COUNTRIES
        .binary_search_by(|(c, _)| (*c).cmp(cc))
        .ok()
        .map(|i| Country {
            cc: COUNTRIES[i].0,
            name: COUNTRIES[i].1,
        })
}

/// Country of an international number: the longest matching prefix of
/// 3, 2 or 1 digits.
pub fn country_of(raw: &str) -> Option<Country> {
    let number = PhoneNumber::parse(raw).ok()?;
    if !number.is_international() {
        return None;
    }
    let digits = number.as_str().trim_start_matches('+');
    (1..=3)
        .rev()
        .filter_map(|n| digits.get(..n))
        .find_map(country_of_cc)
}

/// Like [`country_of`] but `None` for the home country.
pub fn foreign_country(raw: &str, home_cc: Option<&str>) -> Option<Country> {
    let country = country_of(raw)?;
    let home = home_cc.map(|h| h.trim().trim_start_matches('+'));
    (home != Some(country.cc)).then_some(country)
}

/// Caption for a number from abroad, for example `Germany`.
pub fn country_line(raw: &str, home_cc: Option<&str>) -> Option<String> {
    foreign_country(raw, home_cc).map(|c| c.name.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cc(raw: &str) -> Option<&'static str> {
        country_of(raw).map(|c| c.cc)
    }

    #[test]
    fn finds_countries() {
        assert_eq!(cc("+49 30 1234567"), Some("49"));
        assert_eq!(cc("+1 212 555 0100"), Some("1"));
        assert_eq!(cc("+971 4 123 4567"), Some("971"));
        assert_eq!(cc("+380 44 123 45 67"), Some("380"));
        assert_eq!(cc("0049 30 1234567"), Some("49"));
        assert_eq!(
            country_of("+90 532 111 22 33").map(|c| c.name),
            Some("Turkey")
        );
        assert_eq!(
            country_of("+7 495 123 45 67").map(|c| c.name),
            Some("Russia / Kazakhstan")
        );
    }

    #[test]
    fn rejects_non_international() {
        assert_eq!(cc("+999 123 456"), None);
        assert_eq!(cc("0532 111 22 33"), None);
        assert_eq!(cc("112"), None);
        assert_eq!(cc("Bank"), None);
        assert_eq!(cc(""), None);
    }

    #[test]
    fn exact_lookup() {
        assert_eq!(country_of_cc("44").map(|c| c.name), Some("United Kingdom"));
        assert_eq!(country_of_cc("999"), None);
        assert_eq!(country_of_cc(""), None);
    }

    #[test]
    fn foreign_hides_home() {
        assert_eq!(foreign_country("+90 532 111 22 33", Some("90")), None);
        assert_eq!(foreign_country("+90 532 111 22 33", Some("+90")), None);
        assert_eq!(
            foreign_country("+49 30 1234567", Some("90")).map(|c| c.cc),
            Some("49")
        );
        assert_eq!(
            foreign_country("+49 30 1234567", None).map(|c| c.cc),
            Some("49")
        );
        assert_eq!(
            country_line("+49 30 1234567", Some("90")).as_deref(),
            Some("Germany")
        );
        assert_eq!(country_line("+49 30 1234567", Some("49")), None);
    }

    #[test]
    fn table_is_sorted_without_duplicates() {
        for pair in COUNTRIES.windows(2) {
            assert!(pair[0].0 < pair[1].0, "{} before {}", pair[0].0, pair[1].0);
        }
        for (code, name) in COUNTRIES {
            assert!(!name.is_empty());
            assert!(code.chars().all(|c| c.is_ascii_digit()));
        }
        for n in ["1", "7", "44", "90", "49", "971", "380"] {
            assert!(country_of_cc(n).is_some(), "{n}");
        }
    }
}
