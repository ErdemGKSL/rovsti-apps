//! Rows of the two extra list sections: "In details" under the search
//! results (C41) and "Frequently contacted" under the favorites (C42). The
//! mapping is plain data so it is unit tested; drawing reuses the contact row.

use std::sync::Arc;

use gpui::{Context, SharedString};
use rovsti_android::permissions::{self, Group};
use rovsti_core::{
    contact_find::DeepHit,
    contact_frequent::{self, Frequent},
    contacts_view::{self, ContactVm, NameStyle},
    search::ContactIndex,
    wire::WireContact,
};

use crate::{ContactsApp, Row, backend};

/// Most "In details" rows shown under a query.
pub(crate) const MAX_DEEP: usize = 20;
/// The section titles.
pub(crate) const DEEP_TITLE: &str = "In details";
pub(crate) const FREQUENT_TITLE: &str = "Frequently contacted";

/// What a list index points at when a section follows the main rows.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Slot {
    /// Row `n` of the main list.
    Main(usize),
    /// The section title.
    Title,
    /// Row `n` of the section.
    Extra(usize),
}

/// List items a section of `rows` rows takes: none when empty, else the title
/// and the rows.
pub(crate) fn section_len(rows: usize) -> usize {
    if rows == 0 { 0 } else { rows + 1 }
}

/// Maps the position `i` below the list header onto the main rows or the
/// section after them.
pub(crate) fn slot(i: usize, main: usize, extra: usize) -> Option<Slot> {
    if i < main {
        Some(Slot::Main(i))
    } else if extra > 0 && i == main {
        Some(Slot::Title)
    } else if i > main && i - main - 1 < extra {
        Some(Slot::Extra(i - main - 1))
    } else {
        None
    }
}

/// One row of a section: the contact's usual row with `subtitle` under the
/// name. Contacts that are gone (hidden by a delete) are dropped.
fn section_row(
    index: &ContactIndex,
    id: i64,
    subtitle: String,
    style: NameStyle,
) -> Option<ContactVm> {
    let contact = index.all().find(|c| c.id == id)?;
    let mut vm = contacts_view::build_rows_styled([contact], "", style)
        .into_iter()
        .next()?;
    vm.subtitle = Some(subtitle);
    vm.header = false;
    Some(vm)
}

/// "In details" rows: the name, then "Company · Acme Ltd".
pub(crate) fn deep_rows(
    hits: &[DeepHit],
    index: &ContactIndex,
    style: NameStyle,
) -> Vec<ContactVm> {
    hits.iter()
        .filter_map(|hit| {
            let subtitle = format!("{} · {}", hit.field.label(), hit.snippet);
            section_row(index, hit.id, subtitle, style)
        })
        .collect()
}

/// "Frequently contacted" rows: the person and how often they were called.
/// Starred contacts (just added) and vanished ones are left out.
pub(crate) fn frequent_rows(
    list: &[Frequent],
    index: &ContactIndex,
    style: NameStyle,
) -> Vec<ContactVm> {
    list.iter()
        .filter(|f| index.all().any(|c| c.id == f.contact_id && !c.starred))
        .filter_map(|f| section_row(index, f.contact_id, contact_frequent::subtitle(f), style))
        .take(contact_frequent::MAX_SHOWN)
        .collect()
}

impl ContactsApp {
    /// Builds the details index in the background (once per book reload).
    pub(crate) fn load_deep(&mut self, cx: &mut Context<Self>) {
        self.deep_loads += 1;
        let load = self.deep_loads;
        cx.spawn(async move |this, cx| {
            let built = cx
                .background_executor()
                .spawn(async { backend::deep_index() })
                .await;
            this.update(cx, |this, cx| {
                if this.deep_loads != load {
                    return;
                }
                // A failed read leaves the search to names and numbers.
                this.deep = built.ok().map(Arc::new);
                this.rebuild_rows(cx);
            })
            .ok();
        })
        .detach();
    }

    /// The "In details" rows for `query`, skipping contacts already listed.
    pub(crate) fn deep_rows_for(&self, query: &str, listed: &[Row]) -> Vec<Row> {
        let (Some(deep), Some(index)) = (self.deep.as_ref(), self.index()) else {
            return Vec::new();
        };
        let skip: Vec<i64> = listed.iter().map(|r| r.vm.id).collect();
        let hits = deep.search(query, &skip, MAX_DEEP);
        deep_rows(&hits, index, self.style)
            .into_iter()
            .map(Row::from)
            .collect()
    }

    /// Reads the most contacted people in the background. Only when the
    /// call-log permission is already granted: nothing here asks for it.
    pub(crate) fn load_frequent(&mut self, cx: &mut Context<Self>) {
        self.frequent_loads += 1;
        let load = self.frequent_loads;
        let Some(index) = self.index().cloned() else {
            self.frequent.clear();
            self.frequent_rows.clear();
            return;
        };
        cx.spawn(async move |this, cx| {
            let list = cx
                .background_executor()
                .spawn(async move {
                    if !matches!(permissions::has(Group::CallLog), Ok(true)) {
                        return Vec::new();
                    }
                    let contacts: Vec<WireContact> = index.all().cloned().collect();
                    backend::frequent(&contacts, crate::whatsapp::now_ms()).unwrap_or_default()
                })
                .await;
            this.update(cx, |this, cx| {
                if this.frequent_loads != load {
                    return;
                }
                this.frequent = list;
                this.rebuild_frequent();
                this.refill_others(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Rows of the loaded people against the current book and name style.
    pub(crate) fn rebuild_frequent(&mut self) {
        self.frequent_rows = match self.index() {
            Some(index) => frequent_rows(&self.frequent, index, self.style)
                .into_iter()
                .map(Row::from)
                .collect(),
            None => Vec::new(),
        };
    }

    /// A section title above its rows.
    pub(crate) fn section_title(title: &'static str) -> SharedString {
        title.into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::{
        contact_find::DeepField,
        wire::{WireContact, WireNumber},
    };

    fn contact(id: i64, name: &str, starred: bool) -> WireContact {
        WireContact {
            id,
            display_name: name.into(),
            numbers: vec![WireNumber {
                raw: "+905321112233".into(),
                label: None,
                default: false,
            }],
            starred,
            ..WireContact::default()
        }
    }

    fn index() -> ContactIndex {
        ContactIndex::new(vec![
            contact(1, "Ayşe Yılmaz", false),
            contact(2, "Bob", true),
            contact(3, "Cem", false),
        ])
    }

    fn hit(id: i64, field: DeepField, snippet: &str) -> DeepHit {
        DeepHit {
            id,
            field,
            snippet: snippet.into(),
        }
    }

    #[test]
    fn deep_rows_show_the_field_and_snippet() {
        let hits = [hit(1, DeepField::Company, "Acme Ltd")];
        let rows = deep_rows(&hits, &index(), NameStyle::default());
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].id, 1);
        assert_eq!(rows[0].name, "Ayşe Yılmaz");
        assert_eq!(rows[0].subtitle.as_deref(), Some("Company · Acme Ltd"));
        assert!(!rows[0].header);
    }

    #[test]
    fn deep_rows_drop_contacts_that_are_gone() {
        let hits = [
            hit(9, DeepField::Note, "hidden"),
            hit(3, DeepField::Email, "cem@x.org"),
        ];
        let rows = deep_rows(&hits, &index(), NameStyle::default());
        assert_eq!(rows.iter().map(|r| r.id).collect::<Vec<_>>(), [3]);
        assert_eq!(rows[0].subtitle.as_deref(), Some("Email · cem@x.org"));
    }

    #[test]
    fn frequent_rows_show_the_call_count_and_skip_starred() {
        let list = [
            Frequent {
                contact_id: 2,
                calls: 9,
                last_ms: 5,
            },
            Frequent {
                contact_id: 3,
                calls: 4,
                last_ms: 4,
            },
        ];
        let rows = frequent_rows(&list, &index(), NameStyle::default());
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].id, 3);
        assert_eq!(
            rows[0].subtitle.as_deref(),
            Some("4 calls in the last 30 days")
        );
    }

    #[test]
    fn frequent_section_is_hidden_when_empty() {
        assert!(frequent_rows(&[], &index(), NameStyle::default()).is_empty());
        assert_eq!(section_len(0), 0);
        assert_eq!(slot(3, 3, 0), None);
    }

    #[test]
    fn frequent_rows_are_capped() {
        let people: Vec<WireContact> = (1..=20).map(|i| contact(i, "P", false)).collect();
        let list: Vec<Frequent> = (1..=20)
            .map(|id| Frequent {
                contact_id: id,
                calls: 3,
                last_ms: id,
            })
            .collect();
        let rows = frequent_rows(&list, &ContactIndex::new(people), NameStyle::default());
        assert_eq!(rows.len(), contact_frequent::MAX_SHOWN);
    }

    #[test]
    fn slots_follow_the_main_rows_then_title_then_section() {
        assert_eq!(section_len(2), 3);
        assert_eq!(slot(0, 2, 2), Some(Slot::Main(0)));
        assert_eq!(slot(1, 2, 2), Some(Slot::Main(1)));
        assert_eq!(slot(2, 2, 2), Some(Slot::Title));
        assert_eq!(slot(3, 2, 2), Some(Slot::Extra(0)));
        assert_eq!(slot(4, 2, 2), Some(Slot::Extra(1)));
        assert_eq!(slot(5, 2, 2), None);
    }

    #[test]
    fn slots_without_main_rows_start_at_the_title() {
        assert_eq!(slot(0, 0, 1), Some(Slot::Title));
        assert_eq!(slot(1, 0, 1), Some(Slot::Extra(0)));
    }
}
