use crate::PhoneNumber;

/// Android `ContactsContract.Contacts._ID`, or a synthetic id on desktop.
#[derive(
    Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, serde::Serialize, serde::Deserialize,
)]
pub struct ContactId(pub i64);

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Contact {
    pub id: ContactId,
    pub display_name: String,
    pub numbers: Vec<PhoneNumber>,
    pub starred: bool,
}

impl Contact {
    /// One or two letters for the avatar monogram.
    pub fn initials(&self) -> String {
        self.display_name
            .split_whitespace()
            .filter_map(|word| word.chars().next())
            .take(2)
            .flat_map(char::to_uppercase)
            .collect()
    }

    pub fn has_number(&self, number: &PhoneNumber) -> bool {
        self.numbers.iter().any(|n| n.matches(number))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn initials_use_first_two_words() {
        let contact = Contact {
            id: ContactId(1),
            display_name: "ada lovelace byron".into(),
            numbers: vec![],
            starred: false,
        };
        assert_eq!(contact.initials(), "AL");
    }
}
