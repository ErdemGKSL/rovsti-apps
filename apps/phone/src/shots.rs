//! Headless screenshots of Rovsti Phone (`docs/screenshots.md`).
//!
//! `cargo test -p rovsti_phone --features shots shots -- --ignored --nocapture`

use std::path::PathBuf;

use futures::channel::mpsc;
use gpui::{AppContext as _, Context, Window};
use rovsti_core::call_log_query::RecentsFilter;
use rovsti_ui::shots::{PHONE, Rig, SMALL_PHONE, ShotSpec, contact_sheet, isolate_data_dir};
use rovsti_ui::tokens::Theme;

use crate::{CONTACTS_TAB, FAVORITES_TAB, PhoneApp, RECENTS_TAB, backend::Backend};

/// The instant every shot is taken at: 2026-11-07T15:40:00+03:00.
pub(crate) const SHOT_NOW_MS: i64 = 1_794_055_200_000;

fn open(spec: ShotSpec) -> Rig<PhoneApp> {
    rovsti_core::clock::set_fixed_now_ms(Some(SHOT_NOW_MS));
    let (_tx, rx) = mpsc::unbounded::<String>();
    let mut rig = Rig::open(spec, move |window, cx| {
        let backend = Backend::start(cx);
        cx.new(|cx| {
            let mut app = PhoneApp::new(backend, rx, window, cx);
            app.devtools_visible = false;
            app
        })
    });
    rig.theme();
    rig
}

/// What a scene does to the settled app.
type Scene = fn(&mut PhoneApp, &mut Window, &mut Context<PhoneApp>);

/// Every main screen and important sheet, in shot order.
const SCENES: [(u32, &str, Scene); 13] = [
    (1, "keypad-empty", |_, _, _| {}),
    (2, "keypad-long-number", |app, window, cx| {
        app.open_dial(LONG_NUMBER_URI, window, cx);
    }),
    (3, "recents", |app, _, cx| app.select_tab(RECENTS_TAB, cx)),
    (4, "recents-missed-filter", |app, _, cx| {
        app.select_tab(RECENTS_TAB, cx);
        app.recents_choose_filter(RecentsFilter::Missed, cx);
    }),
    (5, "contacts-list", |app, _, cx| {
        app.select_tab(CONTACTS_TAB, cx)
    }),
    (6, "contacts-search-typing", |app, window, cx| {
        app.select_tab(CONTACTS_TAB, cx);
        let field = app.contacts.field.clone();
        field.update(cx, |field, cx| field.set("Ayş", cx));
        let handle = field.read(cx).focus_handle();
        window.focus(&handle, cx);
    }),
    (7, "favorites", |app, _, cx| {
        app.select_tab(FAVORITES_TAB, cx)
    }),
    (8, "contact-detail", |app, window, cx| {
        app.select_tab(CONTACTS_TAB, cx);
        app.preview_contact_sheet("1", window, cx);
    }),
    (9, "call-details", |app, window, cx| {
        app.select_tab(CONTACTS_TAB, cx);
        app.preview_contact_sheet("1", window, cx);
        app.open_call_details(true, 0, window, cx);
    }),
    (10, "incall", |app, _, cx| {
        app.backend.update(cx, |backend, cx| {
            backend.place_call("+905551112233", cx);
        });
    }),
    (11, "incoming", |app, _, cx| {
        app.backend.update(cx, |backend, cx| {
            backend.preview_ring(Some("Ayşe Yılmaz"), false, false, cx);
        });
    }),
    (12, "settings", |app, _, cx| {
        app.settings.open(false);
        app.settings.enter_snap();
        app.sync_settings_rows(cx, true);
    }),
    (13, "setup-sheet", |app, _, _| app.sheet.open()),
];

/// `+90 532 123 45 67 891 234`: longer than the field fits on one line.
const LONG_NUMBER_URI: &str = "tel:+905321234567891234";

fn render(spec: ShotSpec, scene: Scene) -> PathBuf {
    // Scenes persist the last tab; every shot starts from empty prefs.
    isolate_data_dir("phone");
    let mut rig = open(spec);
    // The stored tab arrives first; scenes act on the settled app.
    rig.settle();
    let root = rig.root.clone();
    rig.update(|cx, window| root.update(cx, |app, cx| scene(app, window, cx)));
    rig.settle();
    rig.save()
}

#[test]
#[ignore = "needs a GPU adapter (lavapipe works) and system fonts"]
fn shots() {
    let mut saved = Vec::new();
    for &(nn, screen, scene) in &SCENES {
        for theme in [Theme::Dark, Theme::Light] {
            let spec = ShotSpec {
                app: "phone",
                nn,
                screen,
                theme,
                size: PHONE,
            };
            let path = render(spec, scene);
            println!("{}", path.display());
            saved.push(path);
        }
    }
    let small = ShotSpec {
        app: "phone",
        nn: 1,
        screen: "keypad-empty",
        theme: Theme::Dark,
        size: SMALL_PHONE,
    };
    let path = render(small, SCENES[0].2);
    println!("{}", path.display());
    saved.push(path);
    let sheet = contact_sheet("phone", &saved).expect("write the contact sheet");
    println!("{}", sheet.display());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scenes_are_numbered_in_order_with_unique_names() {
        for (i, (nn, name, _)) in SCENES.iter().enumerate() {
            assert_eq!(*nn as usize, i + 1);
            assert!(SCENES.iter().filter(|s| s.1 == *name).count() == 1);
        }
    }

    #[test]
    fn the_long_number_is_longer_than_a_keypad_line() {
        let number = rovsti_core::dial::from_tel_uri(LONG_NUMBER_URI);
        assert_eq!(number, "+905321234567891234");
        assert!(number.len() > 15);
    }
}
