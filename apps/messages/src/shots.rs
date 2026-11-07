//! Headless screenshots of Rovsti Messages (`docs/screenshots.md`).
//!
//! `cargo test -p rovsti_messages --features shots shots -- --ignored --nocapture`

mod gateway;

use std::path::PathBuf;

use gpui::{AppContext as _, Context, Window};
use rovsti_core::{ThreadId, inbox_filter::InboxFilter};
use rovsti_ui::shots::{PHONE, Rig, SMALL_PHONE, ShotSpec, contact_sheet, isolate_data_dir};
use rovsti_ui::tokens::Theme;

use crate::{
    MessagesApp, SETTINGS_TAB, WHATSAPP_TAB,
    backend::{
        Backend,
        preview::{AYSE, WHATSAPP_AYSE, shots_data},
    },
};

/// The instant every shot is taken at: 2026-11-07T15:40:00+03:00.
pub(crate) const SHOT_NOW_MS: i64 = 1_794_055_200_000;

fn open(spec: ShotSpec) -> Rig<MessagesApp> {
    rovsti_core::clock::set_fixed_now_ms(Some(SHOT_NOW_MS));
    shots_data::use_shots_fixture(true);
    let mut rig = Rig::open(spec, move |window, cx| {
        let backend = Backend::start(cx);
        let app = cx.new(|cx| MessagesApp::new(backend, cx));
        app.update(cx, |app, cx| app.watch_window(window, cx));
        app
    });
    rig.theme();
    rig
}

/// What a scene does to the settled app.
type Scene = fn(&mut MessagesApp, &mut Window, &mut Context<MessagesApp>);

/// Types `text` into the Chats search row and focuses it.
fn search(app: &mut MessagesApp, text: &str, window: &mut Window, cx: &mut Context<MessagesApp>) {
    app.search_open = true;
    let field = app.search_field.clone();
    field.update(cx, |field, cx| field.set(text, cx));
    let handle = field.read(cx).focus_handle();
    window.focus(&handle, cx);
    cx.notify();
}

/// Every main screen and important sheet, in shot order.
/// `(number, name, needs a linked gateway, what it does)`.
const SCENES: [(u32, &str, bool, Scene); 13] = [
    (1, "inbox", false, |_, _, _| {}),
    (2, "unread-filter", false, |app, _, cx| {
        app.inbox_filter = InboxFilter::Unread;
        cx.notify();
    }),
    (3, "inbox-empty", false, |app, window, cx| {
        search(app, "xyzq", window, cx)
    }),
    (4, "conversation-sms", false, |app, _, cx| {
        app.open_thread(ThreadId::Sms(1), "Ayşe Yılmaz".to_owned(), cx);
    }),
    (5, "conversation-whatsapp-group", true, |app, _, cx| {
        app.open_chat(
            shots_data::GROUP_FAMILY.to_owned(),
            shots_data::FAMILY_TITLE.to_owned(),
            cx,
        );
    }),
    (6, "composer-typing", false, |app, window, cx| {
        app.open_thread(ThreadId::Sms(1), "Ayşe Yılmaz".to_owned(), cx);
        if let crate::Open::Sms(page) = &app.open {
            page.update(cx, |page, cx| {
                page.set_draft(
                    "Tamamdır, yarın akşam 7'de görüşürüz. Pastayı da unutma, \
                     çilekli olsun lütfen!",
                    cx,
                );
            });
            let focus = page.read(cx).composer_focus(cx);
            window.focus(&focus, cx);
        }
    }),
    (7, "new-conversation", false, |app, _, cx| app.open_new(cx)),
    (8, "global-search", false, |app, window, cx| {
        search(app, "yarın", window, cx)
    }),
    (9, "status", true, |app, _, cx| app.open_status(cx)),
    (10, "spam-folder", false, |app, _, cx| {
        app.show_spam = true;
        cx.notify();
    }),
    (11, "row-menu", false, |app, _, cx| {
        app.open_row_menu(
            Some(WHATSAPP_AYSE.to_owned()),
            Some((1, Some(AYSE.to_owned()))),
            "Ayşe Yılmaz".into(),
            cx,
        );
    }),
    (12, "settings", false, |app, _, cx| {
        app.tabs.select(SETTINGS_TAB);
        cx.notify();
    }),
    (13, "whatsapp-link", false, |app, _, cx| {
        app.tabs.select(WHATSAPP_TAB);
        cx.notify();
    }),
];

/// Links the app to the fake gateway at `url`, the way a linked app starts:
/// the saved link is in its data folder.
fn write_link(data: &std::path::Path, url: &str) {
    let dir = data.join("rovsti").join("messages");
    let link = rovsti_wa_ui::StoredLink {
        gateway_url: url.to_owned(),
        instance_id: "shots".to_owned(),
        key: rovsti_wa_proto::ConnectKey::from_bytes(&[7; 32])
            .as_str()
            .to_owned(),
    };
    rovsti_ui::storage::save_json(&dir, rovsti_wa_ui::LINK_FILE, &link)
        .expect("save the WhatsApp link");
}

fn render(spec: ShotSpec, linked: Option<&str>, scene: Scene) -> PathBuf {
    let data = isolate_data_dir("messages");
    if let Some(url) = linked {
        write_link(&data, url);
    }
    let mut rig = open(spec);
    rig.settle();
    let root = rig.root.clone();
    rig.update(|cx, _| {
        root.update(cx, |app, cx| {
            let list = shots_data::statuses(SHOT_NOW_MS);
            app.statuses.update(cx, |feed, cx| feed.set_list(list, cx));
        })
    });
    rig.settle();
    rig.update(|cx, window| root.update(cx, |app, cx| scene(app, window, cx)));
    rig.settle();
    rig.save()
}

#[test]
#[ignore = "needs a GPU adapter (lavapipe works) and system fonts"]
fn shots() {
    let gateway = gateway::serve(SHOT_NOW_MS);
    let mut saved = Vec::new();
    for &(nn, screen, linked, scene) in &SCENES {
        for theme in [Theme::Dark, Theme::Light] {
            let spec = ShotSpec {
                app: "messages",
                nn,
                screen,
                theme,
                size: PHONE,
            };
            let path = render(spec, linked.then_some(gateway.as_str()), scene);
            println!("{}", path.display());
            saved.push(path);
        }
    }
    let small = ShotSpec {
        app: "messages",
        nn: 1,
        screen: "inbox",
        theme: Theme::Dark,
        size: SMALL_PHONE,
    };
    let path = render(small, None, SCENES[0].3);
    println!("{}", path.display());
    saved.push(path);
    let sheet = contact_sheet("messages", &saved).expect("write the contact sheet");
    println!("{}", sheet.display());
}
