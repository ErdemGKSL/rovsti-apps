//! Headless screenshots of Rovsti Phone (`docs/screenshots.md`).
//!
//! `cargo test -p rovsti_phone --features shots shots -- --ignored --nocapture`

use futures::channel::mpsc;
use gpui::AppContext as _;
use rovsti_ui::shots::{PHONE, Rig, ShotSpec, isolate_data_dir};
use rovsti_ui::tokens::Theme;

use crate::{PhoneApp, backend::Backend};

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

#[test]
#[ignore = "needs a GPU adapter (lavapipe works) and system fonts"]
fn shots() {
    isolate_data_dir("phone");
    for theme in [Theme::Dark, Theme::Light] {
        let mut rig = open(ShotSpec {
            app: "phone",
            nn: 0,
            screen: "smoke",
            theme,
            size: PHONE,
        });
        rig.settle();
        println!("{}", rig.save().display());
    }
}
