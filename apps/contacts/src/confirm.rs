//! The confirmation card: "Delete this contact?", "Delete this group?",
//! "Replace the photo?" (with the WhatsApp one), "Discard changes?" on a
//! dirty edit form and "Add these contacts?" after a vCard was read.

use std::sync::Arc;

use gpui::{AnyElement, Context, IntoElement, SharedString, StatefulInteractiveElement};
use rovsti_core::{
    contact_edit::ContactDraft, contact_trash, contacts_view, search::ContactIndex,
    vcard::CardPhoto,
};
use rovsti_ui::tokens::Theme;

use crate::{
    Book, ContactsApp, NoticeAction, PendingDelete, UNDO_LIFE, backend, trash_store,
    ui::{confirm_card, pill},
};

/// What the owner is asked to confirm.
pub(crate) enum Confirm {
    DeleteContact {
        id: i64,
        name: SharedString,
    },
    /// Select mode's Delete: these contacts would be removed.
    DeleteMany {
        ids: Vec<i64>,
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
    /// The viewer's "Remove photo": the contact's own photo would be deleted.
    RemovePhoto {
        id: i64,
        name: SharedString,
    },
    /// Cancel or Back on an edit form with unsaved changes.
    DiscardEdits,
    /// Duplicates would become one contact: the others are deleted.
    MergeContacts {
        keeper: i64,
        others: Vec<i64>,
        name: SharedString,
    },
    /// A vCard file was read: these cards would be added.
    Import {
        cards: Vec<ContactDraft>,
        /// Aligned with `cards`: the picture that came with each card.
        photos: Vec<Option<CardPhoto>>,
        duplicates: usize,
        unreadable: usize,
    },
    /// Settings, Trash: everything in the Trash would be deleted for good.
    EmptyTrash {
        count: usize,
    },
    /// These Trash entries would be deleted for good.
    DeleteForever {
        keys: Vec<u64>,
    },
    /// No copy could be kept in the Trash: delete these anyway?
    DeleteWithoutCopy {
        ids: Vec<i64>,
    },
    /// Turning on "Send calls to voicemail" for this contact.
    Voicemail {
        id: i64,
        name: SharedString,
    },
    /// "Show recent calls": explains why the call log is read, then asks Android.
    CallLogAccess,
    /// The card menu's "Remove my card".
    RemoveMyCard,
    /// The Contacts tab's "Clear" on Recently viewed.
    ClearViewed,
    /// This contact would leave the emergency list.
    RemoveEmergency {
        key: String,
        name: SharedString,
    },
}

/// Title and body of the Trash cards, `None` for the other cards.
fn trash_confirm_text(confirm: &Confirm) -> Option<(String, String)> {
    match confirm {
        Confirm::EmptyTrash { count } => Some(contact_trash::empty_confirm(*count)),
        Confirm::DeleteForever { keys } => Some(contact_trash::forever_confirm(keys.len())),
        Confirm::DeleteWithoutCopy { ids } => Some(crate::trash::no_copy_text(ids.len())),
        _ => None,
    }
}

/// Title and body of the card for clearing Recently viewed.
fn clear_viewed_text() -> (&'static str, &'static str) {
    rovsti_core::contact_recent::CLEAR_VIEWED
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
fn import_summary(add: usize, photos: usize, duplicates: usize, unreadable: usize) -> String {
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
    let with = match photos {
        0 => String::new(),
        1 => " with 1 photo".to_owned(),
        n => format!(" with {n} photos"),
    };
    match (add, parts.is_empty()) {
        (0, true) => "The file has no contacts.".to_owned(),
        (0, false) => format!("Left out: {}.", parts.join(", ")),
        (_, true) => format!("They are saved on this phone{with}."),
        (_, false) if photos > 0 => {
            format!("Left out: {}. The others come{with}.", parts.join(", "))
        }
        (_, false) => format!("Left out: {}.", parts.join(", ")),
    }
}

/// The pill after an import: "12 contacts added · 5 photos" (the photos part
/// only when any were set).
fn import_added_notice(added: usize, photos: usize) -> String {
    let mut text = format!("{} added", contacts_words(added));
    match photos {
        0 => {}
        1 => text.push_str(" · 1 photo"),
        n => text.push_str(&format!(" · {n} photos")),
    }
    text
}

/// Title and body of the card for deleting `n` selected contacts.
fn delete_many_text(n: usize) -> (String, String) {
    rovsti_core::contact_select::delete_confirm(n)
}

/// The title of the remove-photo card.
fn remove_photo_title() -> &'static str {
    "Remove photo?"
}

/// The sentence under it.
fn remove_photo_body(name: &str) -> String {
    format!("{name}'s photo is removed from this phone and from any account it syncs with.")
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
            // The contacts were hidden for the delete: they come back.
            if matches!(confirm, Confirm::DeleteWithoutCopy { .. }) {
                self.reload(cx);
            }
            return;
        }
        match confirm {
            Confirm::DeleteContact { id, .. } => self.delete_contacts(vec![id], cx),
            Confirm::DeleteMany { ids } => {
                self.select = None;
                self.cleanup_forget(&ids);
                self.delete_contacts(ids, cx);
            }
            Confirm::DeleteGroup { id, .. } => self.delete_group(id, cx),
            Confirm::UseWaPhoto { id, jid, .. } => self.use_wa_photo(id, jid, cx),
            Confirm::RemovePhoto { id, .. } => {
                self.viewer = None;
                self.clear_photo(id, cx);
            }
            Confirm::DiscardEdits => self.close_editor(cx),
            Confirm::MergeContacts { keeper, others, .. } => self.run_merge(keeper, others, cx),
            Confirm::Import { cards, photos, .. } => self.add_imported(cards, photos, cx),
            Confirm::EmptyTrash { .. } => self.empty_trash(cx),
            Confirm::DeleteForever { keys } => self.delete_forever(keys, cx),
            Confirm::DeleteWithoutCopy { ids } => self.run_delete(ids, false, cx),
            Confirm::CallLogAccess => self.request_call_log(cx),
            Confirm::Voicemail { id, .. } => self.apply_voicemail(id, true, cx),
            Confirm::RemoveMyCard => self.clear_my_card(cx),
            Confirm::ClearViewed => self.clear_viewed(cx),
            Confirm::RemoveEmergency { key, .. } => self.remove_emergency(&key, cx),
        }
    }

    /// Hides the contacts at once and deletes them for real after
    /// `UNDO_LIFE`, unless the owner taps Undo.
    fn delete_contacts(&mut self, ids: Vec<i64>, cx: &mut Context<Self>) {
        if ids.is_empty() {
            return;
        }
        self.commit_pending_delete(cx);
        self.forget_viewed_contacts(&ids, cx);
        if let Some(index) = self.index() {
            let rest = contacts_view::without(index.all(), &ids);
            self.book = Book::Ready(Arc::new(ContactIndex::new(rest)));
        }
        self.editor = None;
        self.open = None;
        self.open_draft = None;
        self.page.restart();
        self.rebuild(cx);
        self.notice = Some(contact_trash::moved_notice(ids.len()).into());
        self.notice_action = Some(NoticeAction::UndoDelete);
        // A second delete within the window must restart the timer.
        self.notice_timer = None;
        let task = cx.spawn(async move |this, cx| {
            cx.background_executor().timer(UNDO_LIFE).await;
            this.update(cx, |this, cx| this.commit_pending_delete(cx))
                .ok();
        });
        self.pending_delete = Some(PendingDelete { ids, task });
        cx.notify();
    }

    /// Runs the delete that is waiting for its Undo window, now.
    pub(crate) fn commit_pending_delete(&mut self, cx: &mut Context<Self>) {
        let Some(PendingDelete { ids, task }) = self.pending_delete.take() else {
            return;
        };
        drop(task);
        // The button is gone with the delete; the text expires on its timer.
        self.notice_action = None;
        cx.notify();
        self.run_delete(ids, true, cx);
    }

    /// Deletes the contacts for real. With `keep_copy` their cards go to the
    /// Trash first; when that fails the owner is asked before they are lost.
    pub(crate) fn run_delete(&mut self, ids: Vec<i64>, keep_copy: bool, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            let total = ids.len();
            let mut kept: Vec<(i64, u64)> = Vec::new();
            if keep_copy {
                let wanted = ids.clone();
                let fetched = cx
                    .background_executor()
                    .spawn(async move { backend::drafts_of(&wanted) })
                    .await;
                let prepared = this
                    .update(cx, |this, _| {
                        fetched.ok().and_then(|drafts| this.keep_copies(drafts))
                    })
                    .ok()
                    .flatten();
                let saved = match prepared {
                    Some(copies) if copies.entries.is_empty() => Some(copies.entries),
                    Some(copies) => {
                        let (dir, snapshot) = (copies.dir.clone(), copies.snapshot.clone());
                        let written = cx
                            .background_executor()
                            .spawn(async move { trash_store::trash_save(&dir, &snapshot) })
                            .await;
                        if let Err(error) = &written {
                            log::warn!("could not save the Trash: {error}");
                            let keys: Vec<u64> = copies.entries.iter().map(|(_, k)| *k).collect();
                            this.update(cx, |this, _| this.trash.remove(&keys)).ok();
                        }
                        written.ok().map(|()| copies.entries)
                    }
                    None => None,
                };
                match saved {
                    Some(entries) => kept = entries,
                    None => {
                        this.update(cx, |this, cx| {
                            this.confirm = Some(Confirm::DeleteWithoutCopy { ids });
                            cx.notify();
                        })
                        .ok();
                        return;
                    }
                }
            }
            let (failed, words) = cx
                .background_executor()
                .spawn(async move {
                    let mut failed = Vec::new();
                    let mut words = String::new();
                    for id in ids {
                        if let Err(error) = backend::delete(id) {
                            failed.push(id);
                            words = error;
                        }
                    }
                    (failed, words)
                })
                .await;
            this.update(cx, |this, cx| {
                if !failed.is_empty() {
                    // What was not deleted must not also sit in the Trash.
                    let keys: Vec<u64> = kept
                        .iter()
                        .filter(|(id, _)| failed.contains(id))
                        .map(|(_, key)| *key)
                        .collect();
                    if !keys.is_empty() {
                        this.trash.remove(&keys);
                        this.save_trash_quietly(cx);
                    }
                    this.notice = Some(
                        if total == 1 {
                            format!("Can't delete the contact: {words}.")
                        } else {
                            format!("Can't delete {}: {words}.", contacts_words(failed.len()))
                        }
                        .into(),
                    );
                }
                this.reload(cx);
            })
            .ok();
        })
        .detach();
    }

    /// The owner tapped Undo: the contact comes back and its card opens.
    pub(crate) fn undo_delete(&mut self, cx: &mut Context<Self>) {
        let Some(pending) = self.pending_delete.take() else {
            self.notice = None;
            self.notice_action = None;
            cx.notify();
            return;
        };
        self.notice = None;
        self.notice_action = None;
        // One contact comes back with its card; several just reappear.
        if let [id] = pending.ids[..] {
            self.pending_open = Some(id);
        }
        self.reload(cx);
        cx.notify();
    }

    fn add_imported(
        &mut self,
        cards: Vec<ContactDraft>,
        photos: Vec<Option<CardPhoto>>,
        cx: &mut Context<Self>,
    ) {
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
                .spawn(async move { backend::import_with_photos(&cards, &photos) })
                .await;
            this.update(cx, |this, cx| {
                this.notice = Some(match result {
                    Ok((added, photos)) => import_added_notice(added as usize, photos).into(),
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
        if let Some((title, body)) = trash_confirm_text(confirm) {
            let label = match confirm {
                Confirm::EmptyTrash { .. } => "Empty Trash",
                Confirm::DeleteForever { .. } => "Delete forever",
                _ => "Delete",
            };
            return Some(confirm_card(
                theme,
                title,
                body,
                vec![cancel, yes(label, true, cx)],
            ));
        }
        Some(match confirm {
            Confirm::DeleteContact { name, .. } => confirm_card(
                theme,
                format!("Delete {name}?"),
                "The contact is removed from this phone and from any account it syncs with.",
                vec![cancel, yes("Delete", true, cx)],
            ),
            Confirm::DeleteMany { ids } => {
                let (title, body) = delete_many_text(ids.len());
                confirm_card(theme, title, body, vec![cancel, yes("Delete", true, cx)])
            }
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
            Confirm::RemovePhoto { name, .. } => confirm_card(
                theme,
                remove_photo_title(),
                remove_photo_body(name),
                vec![cancel, yes("Remove", true, cx)],
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
            Confirm::MergeContacts { others, name, .. } => confirm_card(
                theme,
                crate::dupes::confirm_title(others.len() + 1, name),
                crate::dupes::confirm_body(others.len()),
                vec![cancel, yes("Merge", false, cx)],
            ),
            Confirm::Voicemail { name, .. } => {
                let (title, body) = Self::voicemail_card_text(name);
                confirm_card(theme, title, body, vec![cancel, yes("Send", false, cx)])
            }
            Confirm::RemoveMyCard => confirm_card(
                theme,
                "Remove my card?",
                "No contact is marked as you. The contact itself stays in your address book.",
                vec![cancel, yes("Remove", true, cx)],
            ),
            Confirm::ClearViewed => {
                let (title, body) = clear_viewed_text();
                confirm_card(theme, title, body, vec![cancel, yes("Clear", true, cx)])
            }
            Confirm::RemoveEmergency { name, .. } => confirm_card(
                theme,
                format!("Remove {name}?"),
                "They leave the emergency list. The contact stays in your address book.",
                vec![cancel, yes("Remove", true, cx)],
            ),
            Confirm::CallLogAccess => {
                let (title, body) = Self::call_log_card_text();
                confirm_card(theme, title, body, vec![cancel, yes("Allow", false, cx)])
            }
            Confirm::EmptyTrash { .. }
            | Confirm::DeleteForever { .. }
            | Confirm::DeleteWithoutCopy { .. } => return None,
            Confirm::Import {
                cards,
                photos,
                duplicates,
                unreadable,
            } => {
                let with_photo = photos.iter().flatten().count();
                let summary = import_summary(cards.len(), with_photo, *duplicates, *unreadable);
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
    fn the_remove_photo_card_names_the_contact() {
        assert_eq!(remove_photo_title(), "Remove photo?");
        assert_eq!(
            remove_photo_body("Ada"),
            "Ada's photo is removed from this phone and from any account it syncs with."
        );
    }

    #[test]
    fn the_delete_many_card_counts_the_contacts() {
        let (title, body) = delete_many_text(3);
        assert_eq!(title, "Delete 3 contacts?");
        assert!(body.contains("removed from the phone"));
        assert_eq!(delete_many_text(1).0, "Delete this contact?");
    }

    #[test]
    fn the_trash_cards_say_what_is_lost() {
        let (title, body) =
            trash_confirm_text(&Confirm::EmptyTrash { count: 3 }).expect("empty text");
        assert_eq!(title, "Empty Trash?");
        assert_eq!(
            body,
            "3 contacts will be deleted for good. This can't be undone."
        );
        let (title, body) =
            trash_confirm_text(&Confirm::DeleteForever { keys: vec![4] }).expect("forever text");
        assert_eq!(title, "Delete forever?");
        assert!(body.starts_with("1 contact will"));
        let (title, body) = trash_confirm_text(&Confirm::DeleteWithoutCopy { ids: vec![1, 2] })
            .expect("no copy text");
        assert_eq!(title, "Delete anyway?");
        assert!(body.contains("Can't keep a copy in Trash"));
        assert!(trash_confirm_text(&Confirm::DiscardEdits).is_none());
    }

    #[test]
    fn clear_viewed_card_says_the_contacts_stay() {
        let (title, body) = clear_viewed_text();
        assert_eq!(title, "Clear recently viewed?");
        assert!(body.contains("The contacts stay"));
        assert!(trash_confirm_text(&Confirm::ClearViewed).is_none());
    }

    #[test]
    fn counts_read_naturally() {
        assert_eq!(contacts_words(1), "1 contact");
        assert_eq!(contacts_words(0), "0 contacts");
        assert_eq!(contacts_words(12), "12 contacts");
    }

    #[test]
    fn the_import_summary_names_what_is_left_out() {
        assert_eq!(import_summary(5, 0, 0, 0), "They are saved on this phone.");
        assert_eq!(import_summary(0, 0, 0, 0), "The file has no contacts.");
        assert_eq!(
            import_summary(2, 0, 1, 3),
            "Left out: 1 contact already in your address book, \
             3 contacts without a name, or with a broken number or email."
        );
    }

    #[test]
    fn the_import_summary_mentions_photos() {
        assert_eq!(
            import_summary(5, 5, 0, 0),
            "They are saved on this phone with 5 photos."
        );
        assert_eq!(
            import_summary(2, 1, 0, 0),
            "They are saved on this phone with 1 photo."
        );
        assert_eq!(
            import_summary(2, 2, 1, 0),
            "Left out: 1 contact already in your address book. The others come with 2 photos."
        );
        assert!(!import_summary(0, 0, 1, 0).contains("photo"));
    }

    #[test]
    fn the_import_pill_counts_photos_only_when_any() {
        assert_eq!(import_added_notice(12, 5), "12 contacts added · 5 photos");
        assert_eq!(import_added_notice(1, 1), "1 contact added · 1 photo");
        assert_eq!(import_added_notice(12, 0), "12 contacts added");
    }
}
