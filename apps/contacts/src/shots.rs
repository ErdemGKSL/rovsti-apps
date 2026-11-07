//! Headless screenshots of Rovsti Contacts (`docs/screenshots.md`).
//!
//! `cargo test -p rovsti_contacts --features shots shots -- --ignored --nocapture`

use std::path::PathBuf;

use futures::channel::mpsc;
use gpui::{AppContext as _, Context, Window};
use rovsti_ui::shots::{PHONE, Rig, SMALL_PHONE, ShotSpec, contact_sheet, isolate_data_dir};
use rovsti_ui::tokens::Theme;

use crate::{
    CONTACTS_TAB, ContactsApp, FAVORITES_TAB, GROUPS_TAB, SETTINGS_TAB,
    sample::{self, rich},
};

/// The instant every shot is taken at: 2026-11-07T15:40:00+03:00.
pub(crate) const SHOT_NOW_MS: i64 = 1_794_055_200_000;

/// The contact most scenes open: Ayşe Yılmaz has every kind of field.
const AYSE: i64 = 1;

fn open(spec: ShotSpec) -> Rig<ContactsApp> {
    rovsti_core::clock::set_fixed_now_ms(Some(SHOT_NOW_MS));
    rich::enable();
    let (_tx, rx) = mpsc::unbounded();
    let mut rig = Rig::open(spec, move |window, cx| {
        cx.new(|cx| ContactsApp::new(rx, window, cx))
    });
    rig.theme();
    rig
}

/// What a scene does to the settled app.
type Scene = fn(&mut ContactsApp, &mut Window, &mut Context<ContactsApp>);

/// Every main screen and important sheet, in shot order.
const SCENES: [(u32, &str, Scene); 13] = [
    (1, "list", |_, _, _| {}),
    (2, "list-search", |app, window, cx| {
        let field = app.field.clone();
        field.update(cx, |field, cx| field.set("ş", cx));
        let handle = field.read(cx).focus_handle();
        window.focus(&handle, cx);
    }),
    (3, "favorites", |app, _, cx| {
        app.tabs.select(FAVORITES_TAB);
        app.load_frequent(cx);
    }),
    (4, "groups", |app, _, _| {
        app.tabs.select(GROUPS_TAB);
    }),
    (5, "detail", |app, _, cx| app.open_contact(AYSE, cx)),
    (6, "editor", |app, window, cx| {
        let draft = sample::draft(AYSE).expect("the sample has Ayşe");
        app.open_new_editor_with(draft, cx);
        let focus = app.editor.as_ref().map(|editor| editor.given_focus(cx));
        if let Some(focus) = focus {
            window.focus(&focus, cx);
        }
    }),
    (7, "me-card", |app, _, cx| {
        app.set_my_card(AYSE, cx);
        app.open_contact(AYSE, cx);
    }),
    (8, "birthdays", |app, _, cx| {
        app.tabs.select(FAVORITES_TAB);
        app.set_upcoming_open(true, cx);
    }),
    (9, "dupes", |app, _, cx| {
        app.tabs.select(SETTINGS_TAB);
        app.set_dupes_open(true, cx);
    }),
    (10, "trash-empty", |app, _, cx| {
        app.tabs.select(SETTINGS_TAB);
        app.set_trash_open(true, cx);
    }),
    (11, "select-mode", |app, _, cx| {
        app.tabs.select(CONTACTS_TAB);
        app.enter_select(Some(AYSE), cx);
        app.toggle_selected(2, cx);
        app.toggle_selected(10, cx);
    }),
    (12, "qr-sheet", |app, _, cx| {
        app.open_contact(AYSE, cx);
        app.open_draft = sample::draft(AYSE);
        app.open_qr(cx);
    }),
    (13, "settings", |app, _, _| {
        app.tabs.select(SETTINGS_TAB);
    }),
];

fn render(spec: ShotSpec, scene: Scene) -> PathBuf {
    // Scenes persist prefs and the trash; every shot starts from an empty dir.
    isolate_data_dir("contacts");
    let mut rig = open(spec);
    // The book, groups and saved prefs arrive first; scenes act on the settled app.
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
                app: "contacts",
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
        app: "contacts",
        nn: 1,
        screen: "list",
        theme: Theme::Dark,
        size: SMALL_PHONE,
    };
    let path = render(small, SCENES[0].2);
    println!("{}", path.display());
    saved.push(path);
    let sheet = contact_sheet("contacts", &saved).expect("write the contact sheet");
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
    fn the_run_writes_two_themes_per_scene_and_one_small_shot() {
        assert_eq!(SCENES.len() * 2 + 1, 27);
    }

    #[test]
    fn the_shot_instant_is_the_birthday_the_fixture_celebrates() {
        // 2026-11-07 in Istanbul (UTC+3) is "--11-07" in the fixture.
        let (_, month, day) = rovsti_core::contact_dates::local_ymd(SHOT_NOW_MS, 3 * 3600);
        assert_eq!(format!("--{month:02}-{day:02}"), rich::BIRTHDAY_TODAY);
    }
}
