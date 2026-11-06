//! The confirmation card: "Delete this contact?", "Delete this group?",
//! "Replace the photo?" (with the WhatsApp one), "Discard changes?" on a
//! dirty edit form and "Add these contacts?" after a vCard was read.

use gpui::{AnyElement, Context, IntoElement, SharedString, StatefulInteractiveElement};
use rovsti_core::contact_edit::ContactDraft;
use rovsti_ui::tokens::Theme;

use crate::{
    ContactsApp, backend,
    ui::{confirm_card, pill},
};

/// What the owner is asked to confirm.
pub(crate) enum Confirm {
    DeleteContact {
        id: i64,
        name: SharedString,
    },
    DeleteGroup {
        id: i64,
        title: SharedString,
    },
    /// The WhatsApp picture of `jid` would replace the contact's photo.
    UseWaPhoto {
        id: i64,
        jid: String,
        name: SharedString,
    },
    /// Cancel or Back on an edit form with unsaved changes.
    DiscardEdits,
    /// A vCard file was read: these cards would be added.
    Import {
        cards: Vec<ContactDraft>,
        duplicates: usize,
        unreadable: usize,
    },
}

/// "1 contact", "3 contacts".
fn contacts_words(count: usize) -> String {
    if count == 1 {
        "1 contact".to_owned()
    } else {
        format!("{count} contacts")
    }
}

/// The sentence under the title of the import card.
fn import_summary(add: usize, duplicates: usize, unreadable: usize) -> String {
    let mut parts = Vec::new();
    if duplicates > 0 {
        parts.push(format!(
            "{} already in your address book",
            contacts_words(duplicates)
        ));
    }
    if unreadable > 0 {
        parts.push(format!(
            "{} without a name, or with a broken number or email",
            contacts_words(unreadable)
        ));
    }
    match (add, parts.is_empty()) {
        (0, true) => "The file has no contacts.".to_owned(),
        (0, false) => format!("Left out: {}.", parts.join(", ")),
        (_, true) => "They are saved on this phone.".to_owned(),
        (_, false) => format!("Left out: {}.", parts.join(", ")),
    }
}

impl ContactsApp {
    pub(crate) fn ask_delete_contact(
        &mut self,
        id: i64,
        name: SharedString,
        cx: &mut Context<Self>,
    ) {
        self.confirm = Some(Confirm::DeleteContact { id, name });
        cx.notify();
    }

    pub(crate) fn ask_delete_group(
        &mut self,
        id: i64,
        title: SharedString,
        cx: &mut Context<Self>,
    ) {
        self.confirm = Some(Confirm::DeleteGroup { id, title });
        cx.notify();
    }

    pub(crate) fn ask_use_wa_photo(
        &mut self,
        id: i64,
        jid: String,
        name: SharedString,
        cx: &mut Context<Self>,
    ) {
        self.confirm = Some(Confirm::UseWaPhoto { id, jid, name });
        cx.notify();
    }

    /// The owner answered the card.
    pub(crate) fn answer(&mut self, yes: bool, cx: &mut Context<Self>) {
        let Some(confirm) = self.confirm.take() else {
            return;
        };
        cx.notify();
        if !yes {
            return;
        }
        match confirm {
            Confirm::DeleteContact { id, .. } => self.delete_contact(id, cx),
            Confirm::DeleteGroup { id, .. } => self.delete_group(id, cx),
            Confirm::UseWaPhoto { id, jid, .. } => self.use_wa_photo(id, jid, cx),
            Confirm::DiscardEdits => self.close_editor(cx),
            Confirm::Import { cards, .. } => self.add_imported(cards, cx),
        }
    }

    fn delete_contact(&mut self, id: i64, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::delete(id) })
                .await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(()) => {
                        this.editor = None;
                        this.open = None;
                        this.open_draft = None;
                        this.page.restart();
                        this.notice = Some("Contact deleted.".into());
                    }
                    Err(words) => {
                        this.notice = Some(format!("Can't delete the contact: {words}.").into());
                    }
                }
                this.reload(cx);
            })
            .ok();
        })
        .detach();
    }

    fn add_imported(&mut self, cards: Vec<ContactDraft>, cx: &mut Context<Self>) {
        if cards.is_empty() {
            return;
        }
        // No second import until the added cards are in the book: it would
        // be planned against the old one and add them again.
        self.transfer_busy = true;
        self.notice = Some("Adding contacts…".into());
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { backend::add_contacts(cards) })
                .await;
            this.update(cx, |this, cx| {
                this.notice = Some(match result {
                    Ok(added) => format!("Added {}.", contacts_words(added as usize)).into(),
                    Err(words) => format!("Can't add the contacts: {words}.").into(),
                });
                this.reload(cx);
                this.import_settle = Some(this.pulls);
            })
            .ok();
        })
        .detach();
    }

    /// The card, over the page, while one is open.
    pub(crate) fn confirm_view(&self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        let confirm = self.confirm.as_ref()?;
        let cancel = pill(theme, "confirm-no", "Cancel", false)
            .on_click(cx.listener(|this, _, _, cx| this.answer(false, cx)))
            .into_any_element();
        let yes = |label: &'static str, danger: bool, cx: &mut Context<Self>| {
            pill(theme, "confirm-yes", label, danger)
                .on_click(cx.listener(|this, _, _, cx| this.answer(true, cx)))
                .into_any_element()
        };
        Some(match confirm {
            Confirm::DeleteContact { name, .. } => confirm_card(
                theme,
                format!("Delete {name}?"),
                "The contact is removed from this phone and from any account it syncs with.",
                vec![cancel, yes("Delete", true, cx)],
            ),
            Confirm::DeleteGroup { title, .. } => confirm_card(
                theme,
                format!("Delete “{title}”?"),
                "The group goes away. The contacts in it stay in your address book.",
                vec![cancel, yes("Delete", true, cx)],
            ),
            Confirm::UseWaPhoto { name, .. } => confirm_card(
                theme,
                "Replace the photo?",
                format!("{name}'s photo is replaced with their WhatsApp photo."),
                vec![cancel, yes("Replace", false, cx)],
            ),
            Confirm::DiscardEdits => {
                let keep = pill(theme, "confirm-no", "Keep Editing", false)
                    .on_click(cx.listener(|this, _, _, cx| this.answer(false, cx)))
                    .into_any_element();
                confirm_card(
                    theme,
                    "Discard changes?",
                    "Your changes to this contact are not saved.",
                    vec![keep, yes("Discard", true, cx)],
                )
            }
            Confirm::Import {
                cards,
                duplicates,
                unreadable,
            } => {
                let summary = import_summary(cards.len(), *duplicates, *unreadable);
                if cards.is_empty() {
                    let ok = pill(theme, "confirm-no", "OK", false)
                        .on_click(cx.listener(|this, _, _, cx| this.answer(false, cx)))
                        .into_any_element();
                    confirm_card(theme, "Nothing to add", summary, vec![ok])
                } else {
                    confirm_card(
                        theme,
                        format!("Add {}?", contacts_words(cards.len())),
                        summary,
                        vec![cancel, yes("Add", false, cx)],
                    )
                }
            }
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_read_naturally() {
        assert_eq!(contacts_words(1), "1 contact");
        assert_eq!(contacts_words(0), "0 contacts");
        assert_eq!(contacts_words(12), "12 contacts");
    }

    #[test]
    fn the_import_summary_names_what_is_left_out() {
        assert_eq!(import_summary(5, 0, 0), "They are saved on this phone.");
        assert_eq!(import_summary(0, 0, 0), "The file has no contacts.");
        assert_eq!(
            import_summary(2, 1, 3),
            "Left out: 1 contact already in your address book, \
             3 contacts without a name, or with a broken number or email."
        );
    }
}
