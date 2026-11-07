//! Contacts that come from outside: pasted text, a shared or opened `.vcf`.
//!
//! The clipboard is read only when the owner taps "Paste contact". Files and
//! text shared to the app arrive through a channel fed by `main()`.

use gpui::{ClipboardItem, Context, ReceivedShare};
use rovsti_core::{
    contact_edit::ContactDraft,
    contact_intake::{self, Intake},
    contact_photo::CropView,
    contact_scan::{self, Scanned},
    vcard::CardPhoto,
};

use crate::{
    ContactsApp, backend,
    trash::{self, ScanRoute},
};

/// Something another app handed to Contacts.
pub(crate) enum Incoming {
    /// A link opened with the app (`content://` or `file://` for a file).
    Url(String),
    Share(ReceivedShare),
}

/// Where the contacts came from; it picks the wording of the pills.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Source {
    Paste,
    Shared,
}

/// What to do with an intake.
#[derive(Debug, PartialEq)]
pub(crate) enum IntakeRoute {
    /// The new-contact form, prefilled, and the pill to show.
    Editor(Box<ContactDraft>, &'static str),
    /// The import card (several contacts).
    Import(Vec<ContactDraft>),
    /// A pill only.
    Notice(&'static str),
}

/// Routes `intake` like a scanned code.
pub(crate) fn intake_route(intake: Intake, source: Source) -> IntakeRoute {
    let nothing = || match source {
        Source::Paste => contact_scan::paste_notice(&Scanned::Nothing),
        Source::Shared => "This file has no contacts",
    };
    let scanned = match intake {
        Intake::Cards(cards) => Scanned::Cards(cards),
        Intake::Single(scanned) => scanned,
        Intake::NotContacts => return IntakeRoute::Notice(nothing()),
        other => {
            return IntakeRoute::Notice(contact_intake::notice(&other).unwrap_or_else(nothing));
        }
    };
    match trash::scanned_route(&scanned) {
        ScanRoute::Editor => match trash::scanned_draft(&scanned) {
            Some(draft) => {
                let words = match source {
                    Source::Paste => contact_scan::paste_notice(&scanned),
                    Source::Shared => contact_scan::scan_notice(&scanned),
                };
                IntakeRoute::Editor(Box::new(draft), words)
            }
            None => IntakeRoute::Notice(nothing()),
        },
        ScanRoute::Import => match scanned {
            Scanned::Cards(cards) => IntakeRoute::Import(cards),
            _ => IntakeRoute::Notice(nothing()),
        },
        ScanRoute::Notice => IntakeRoute::Notice(nothing()),
    }
}

/// The photo of the card in `text`, only when `intake` is exactly one card.
pub(crate) fn photo_for(intake: &Intake, text: &str) -> Option<CardPhoto> {
    match intake {
        Intake::Cards(cards) if cards.len() == 1 => backend::single_photo(text),
        _ => None,
    }
}

/// Like [`photo_for`], stored as a temp file for the editor. Blocks.
pub(crate) fn photo_path(intake: &Intake, text: &str) -> Option<String> {
    let photo = photo_for(intake, text)?;
    backend::photo_file(&photo)
        .ok()
        .map(|path| path.to_string_lossy().into_owned())
}

/// The editor photo for a routed intake: only when the route opens the
/// form, so a many-card import never gets one.
pub(crate) fn pending_for(route: &IntakeRoute, path: Option<String>) -> Option<(String, CropView)> {
    match route {
        IntakeRoute::Editor(..) => path.map(|path| (path, CropView::default())),
        _ => None,
    }
}

/// The pill of an opened form: the photo is the news when the card had one.
pub(crate) fn editor_words(words: &'static str, has_photo: bool) -> &'static str {
    if has_photo {
        "Photo added from the card"
    } else {
        words
    }
}

/// The pill when a share held more files than the one that was read.
pub(crate) fn skipped_words(skipped: usize) -> Option<String> {
    match skipped {
        0 => None,
        1 => Some("Opened the first file. 1 other file was skipped.".to_owned()),
        n => Some(format!(
            "Opened the first file. {n} other files were skipped."
        )),
    }
}

/// A link Contacts can read as a file.
fn is_file_url(url: &str) -> bool {
    url.starts_with("content://") || url.starts_with("file://")
}

impl ContactsApp {
    /// Settings, "Paste contact": reads the clipboard now (never earlier).
    pub(crate) fn paste_contact(&mut self, cx: &mut Context<Self>) {
        let text = cx
            .read_from_clipboard()
            .and_then(|item: ClipboardItem| item.text());
        let Some(text) = text else {
            self.route_intake(Intake::NotContacts, Source::Paste, None, cx);
            return;
        };
        let intake = contact_intake::from_text(&text);
        cx.spawn(async move |this, cx| {
            let (intake, photo) = cx
                .background_executor()
                .spawn(async move {
                    let photo = photo_path(&intake, &text);
                    (intake, photo)
                })
                .await;
            this.update(cx, |this, cx| {
                this.route_intake(intake, Source::Paste, photo, cx);
            })
            .ok();
        })
        .detach();
    }

    /// Sends an intake where it belongs.
    ///
    /// `photo` is the stored picture of a single card; the form shows it as
    /// its pending photo.
    pub(crate) fn route_intake(
        &mut self,
        intake: Intake,
        source: Source,
        photo: Option<String>,
        cx: &mut Context<Self>,
    ) {
        let route = intake_route(intake, source);
        let pending = pending_for(&route, photo);
        match route {
            IntakeRoute::Editor(draft, words) => {
                self.open_new_editor_with(*draft, cx);
                let has_photo = pending.is_some();
                if let Some(editor) = self.editor.as_mut() {
                    editor.pending_photo = pending;
                }
                self.notice = Some(editor_words(words, has_photo).into());
            }
            IntakeRoute::Import(cards) => self.plan_cards(cards, cx),
            IntakeRoute::Notice(words) => self.notice = Some(words.into()),
        }
        cx.notify();
    }

    /// Takes the intake that waited for the book to load.
    pub(crate) fn run_pending_intake(&mut self, cx: &mut Context<Self>) {
        if let Some((intake, skipped, photo)) = self.pending_intake.take() {
            self.deliver_intake(intake, skipped, photo, cx);
        }
    }

    /// Routes a shared intake now, or keeps it until the book is loaded.
    fn deliver_intake(
        &mut self,
        intake: Intake,
        skipped: usize,
        photo: Option<String>,
        cx: &mut Context<Self>,
    ) {
        if matches!(self.book, backend::Book::Loading) {
            self.pending_intake = Some((intake, skipped, photo));
            return;
        }
        let usable = matches!(intake, Intake::Cards(_) | Intake::Single(_));
        self.route_intake(intake, Source::Shared, photo, cx);
        if usable && let Some(words) = skipped_words(skipped) {
            self.notice = Some(words.into());
            cx.notify();
        }
    }

    /// One link or share from another app.
    pub(crate) fn receive_incoming(&mut self, incoming: Incoming, cx: &mut Context<Self>) {
        match incoming {
            Incoming::Url(url) => {
                if !is_file_url(&url) {
                    log::info!("ignored a link that is not a file");
                    return;
                }
                cx.spawn(async move |this, cx| {
                    let result = cx
                        .background_executor()
                        .spawn(async move { backend::shared_file(&url) })
                        .await;
                    this.update(cx, |this, cx| match result {
                        Ok((intake, photo)) => this.deliver_intake(intake, 0, photo, cx),
                        Err(words) => {
                            this.notice = Some(format!("Can't open the file: {words}.").into());
                            cx.notify();
                        }
                    })
                    .ok();
                })
                .detach();
            }
            Incoming::Share(share) => {
                let skipped = share.files.len().saturating_sub(1);
                let mime = share.mime_type;
                let Some(file) = share.files.into_iter().next() else {
                    if let Some(text) = share.text {
                        let intake = contact_intake::from_text(&text);
                        cx.spawn(async move |this, cx| {
                            let (intake, photo) = cx
                                .background_executor()
                                .spawn(async move {
                                    let photo = photo_path(&intake, &text);
                                    (intake, photo)
                                })
                                .await;
                            this.update(cx, |this, cx| this.deliver_intake(intake, 0, photo, cx))
                                .ok();
                        })
                        .detach();
                    }
                    return;
                };
                let name = file.name().to_owned();
                let read = file.read_limited(contact_intake::MAX_SHARED_BYTES + 1);
                cx.spawn(async move |this, cx| {
                    let (intake, photo) = match read.await {
                        Ok(bytes) => {
                            cx.background_executor()
                                .spawn(async move {
                                    let intake =
                                        contact_intake::from_file(mime.as_deref(), &name, &bytes);
                                    let photo = contact_intake::decode(&bytes)
                                        .and_then(|text| photo_path(&intake, &text));
                                    (intake, photo)
                                })
                                .await
                        }
                        Err(error) => {
                            log::warn!("could not read the shared file: {error}");
                            (Intake::Unreadable, None)
                        }
                    };
                    this.update(cx, |this, cx| {
                        this.deliver_intake(intake, skipped, photo, cx)
                    })
                    .ok();
                })
                .detach();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::{contact_edit::ContactDraft, vcard};

    fn card(given: &str) -> ContactDraft {
        ContactDraft {
            given: given.to_owned(),
            ..ContactDraft::blank()
        }
    }

    #[test]
    fn one_card_opens_the_editor() {
        let route = intake_route(Intake::Cards(vec![card("Ada")]), Source::Paste);
        assert!(matches!(
            route,
            IntakeRoute::Editor(_, "Contact found on the clipboard")
        ));
        let route = intake_route(Intake::Cards(vec![card("Ada")]), Source::Shared);
        assert!(matches!(route, IntakeRoute::Editor(_, "Contact found")));
    }

    #[test]
    fn many_cards_go_to_the_import_preview() {
        let route = intake_route(Intake::Cards(vec![card("A"), card("B")]), Source::Shared);
        assert!(matches!(route, IntakeRoute::Import(c) if c.len() == 2));
    }

    #[test]
    fn a_pasted_name_and_number_opens_the_editor_prefilled() {
        let intake = contact_intake::from_text("Ayse Yilmaz +90 532 111 22 33");
        match intake_route(intake, Source::Paste) {
            IntakeRoute::Editor(draft, _) => {
                assert_eq!(draft.given, "Ayse");
                assert_eq!(draft.family, "Yilmaz");
                assert_eq!(draft.numbers.len(), 1);
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn a_bare_number_opens_the_editor() {
        let route = intake_route(
            Intake::Single(Scanned::Number("0532".into())),
            Source::Paste,
        );
        assert!(matches!(route, IntakeRoute::Editor(..)));
    }

    #[test]
    fn nothing_usable_is_a_pill() {
        assert_eq!(
            intake_route(Intake::NotContacts, Source::Paste),
            IntakeRoute::Notice("Nothing to add on the clipboard")
        );
        assert_eq!(
            intake_route(Intake::NotContacts, Source::Shared),
            IntakeRoute::Notice("This file has no contacts")
        );
        assert_eq!(
            intake_route(Intake::TooBig, Source::Shared),
            IntakeRoute::Notice("This file is too big to open")
        );
        assert_eq!(
            intake_route(Intake::Unreadable, Source::Paste),
            IntakeRoute::Notice("Can't read this file")
        );
        assert_eq!(
            intake_route(Intake::Cards(Vec::new()), Source::Shared),
            IntakeRoute::Notice("This file has no contacts")
        );
    }

    #[test]
    fn the_photo_is_kept_only_for_one_card() {
        let photo = backend::card_photo("content://x/1").expect("photo");
        let draft = card("Ada");
        let one = vcard::write_one(&draft, Some(&photo));
        let two = format!("{one}{}", vcard::write_one(&card("Bob"), Some(&photo)));
        let single = Intake::Cards(vec![card("Ada")]);
        let both = Intake::Cards(vec![card("Ada"), card("Bob")]);
        assert_eq!(photo_for(&single, &one).as_ref(), Some(&photo));
        assert_eq!(photo_for(&both, &two), None);
        assert_eq!(photo_for(&Intake::NotContacts, &one), None);
        assert_eq!(
            photo_for(&single, "BEGIN:VCARD\r\nFN:X\r\nEND:VCARD\r\n"),
            None
        );
        assert!(photo_path(&single, &one).is_some());
        assert!(photo_path(&both, &two).is_none());
    }

    #[test]
    fn pending_photo_only_when_the_form_opens() {
        let path = Some("/tmp/p.jpg".to_owned());
        let editor = intake_route(Intake::Cards(vec![card("Ada")]), Source::Shared);
        let pending = pending_for(&editor, path.clone()).expect("pending");
        assert_eq!(pending.0, "/tmp/p.jpg");
        assert_eq!(pending.1, CropView::default());
        assert!(pending_for(&editor, None).is_none());
        let import = intake_route(Intake::Cards(vec![card("A"), card("B")]), Source::Shared);
        assert!(pending_for(&import, path.clone()).is_none());
        let notice = intake_route(Intake::NotContacts, Source::Shared);
        assert!(pending_for(&notice, path).is_none());
    }

    #[test]
    fn the_photo_pill_wins_over_the_found_pill() {
        assert_eq!(
            editor_words("Contact found", true),
            "Photo added from the card"
        );
        assert_eq!(editor_words("Contact found", false), "Contact found");
    }

    #[test]
    fn skipped_files_are_counted() {
        assert_eq!(skipped_words(0), None);
        assert!(skipped_words(1).expect("one").contains("1 other file was"));
        assert!(skipped_words(3).expect("three").contains("3 other files"));
    }

    #[test]
    fn only_file_links_are_read() {
        assert!(is_file_url("content://x/1"));
        assert!(is_file_url("file:///a.vcf"));
        assert!(!is_file_url("https://example.com"));
    }
}
