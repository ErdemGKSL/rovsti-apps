//! `cards.*`: share cards (X.2) over `rosty_app_core::cards`.
//!
//! - `cards.templates` answers the template ids, which ones move, the text keys each prints (and the ones it needs),
//!   the still and moving sizes and the palette ids.
//! - `cards.render {spec, out}` draws the card into the file `out` (a PNG, or a one-shot GIF when `spec.animated` and
//!   the template moves) and answers `{path, width, height, bytes, frames}`. The file appears whole or not at all.
//! - `cards.cancel {out}` stops the running render of `out` between two frames (`{cancelled}` says whether one was
//!   running). The cancelled render answers `invalid` / `cancelled` and leaves no file.
//!
//! Errors carry a stable code as `message`, never a sentence: `text_missing` (`detail.key`), `out_invalid`,
//! `out_unwritable` (`io`), `cancelled`, `render_failed`, `font_failed`, `style_failed`, `encode_failed`. A missing or
//! unreadable photo is not an error: the card draws the initials.
//!
//! Kotlin runs these on `RustDispatcher` like every op, so a render never blocks the main thread.

use super::registry::{Case, OpSpec};
use crate::envelope::{ErrorKind, FfiError};
use crate::op;
use rosty_app_core::cards::{self, ANIMATED, CardError, CardSpec, Palette, STILL, Template};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "cards.templates",
        Option<TemplatesReq>,
        TemplatesAns,
        templates,
        templates_cases
    ),
    op!("cards.render", RenderReq, RenderAns, render, render_cases),
    op!("cards.cancel", CancelReq, CancelAns, cancel, cancel_cases),
];

// ---- cards.templates

#[derive(Deserialize)]
struct TemplatesReq {}

#[derive(Debug, Serialize)]
struct Size {
    width: u32,
    height: u32,
}

#[derive(Debug, Serialize)]
struct TemplateInfo {
    id: Template,
    animatable: bool,
    texts: Vec<&'static str>,
    required: Vec<&'static str>,
    still: Size,
    animated: Option<Size>,
}

#[derive(Debug, Serialize)]
struct TemplatesAns {
    templates: Vec<TemplateInfo>,
    palettes: Vec<Palette>,
}

fn templates(_: Option<TemplatesReq>) -> Result<TemplatesAns, FfiError> {
    Ok(TemplatesAns {
        templates: Template::ALL
            .into_iter()
            .map(|t| TemplateInfo {
                id: t,
                animatable: t.animatable(),
                texts: t.text_keys().to_vec(),
                required: t.required_texts().to_vec(),
                still: Size {
                    width: STILL.0,
                    height: STILL.1,
                },
                animated: t.animatable().then_some(Size {
                    width: ANIMATED.0,
                    height: ANIMATED.1,
                }),
            })
            .collect(),
        palettes: Palette::ALL.to_vec(),
    })
}

fn templates_cases() -> Vec<Case> {
    vec![Case::new(json!({}))]
}

// ---- cards.render

#[derive(Deserialize)]
struct RenderReq {
    spec: CardSpec,
    out: String,
}

#[derive(Debug, Serialize)]
struct RenderAns {
    path: String,
    width: u32,
    height: u32,
    bytes: u64,
    frames: u32,
}

/// The cancel flags of the renders that are running, by output path.
fn running() -> &'static Mutex<HashMap<PathBuf, Arc<AtomicBool>>> {
    static RUNNING: OnceLock<Mutex<HashMap<PathBuf, Arc<AtomicBool>>>> = OnceLock::new();
    RUNNING.get_or_init(Default::default)
}

fn lock() -> std::sync::MutexGuard<'static, HashMap<PathBuf, Arc<AtomicBool>>> {
    running()
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// Removes the render's flag when it ends, however it ends.
struct Registered(PathBuf, Arc<AtomicBool>);

impl Drop for Registered {
    fn drop(&mut self) {
        let mut map = lock();
        if map.get(&self.0).is_some_and(|flag| Arc::ptr_eq(flag, &self.1)) {
            map.remove(&self.0);
        }
    }
}

fn card_error(e: &CardError) -> FfiError {
    let kind = match e {
        CardError::OutUnwritable(_) => ErrorKind::Io,
        _ => ErrorKind::Invalid,
    };
    let error = FfiError::new(kind, e.code());
    match e {
        CardError::TextMissing(key) => error.with_detail(json!({ "key": key })),
        _ => error,
    }
}

fn render(req: RenderReq) -> Result<RenderAns, FfiError> {
    let out = PathBuf::from(&req.out);
    if req.out.trim().is_empty() || !out.is_absolute() || out.file_name().is_none() {
        return Err(FfiError::invalid("out_invalid"));
    }
    let flag = Arc::new(AtomicBool::new(false));
    lock().insert(out.clone(), flag.clone());
    let _registered = Registered(out.clone(), flag.clone());
    let done = cards::render(&req.spec, &out, &|| flag.load(Ordering::Relaxed)).map_err(|e| {
        log::warn!(
            "cards.render {:?} failed: {}",
            req.spec.template,
            e.code()
        );
        card_error(&e)
    })?;
    Ok(RenderAns {
        path: req.out,
        width: done.width,
        height: done.height,
        bytes: done.bytes,
        frames: done.frames,
    })
}

fn render_cases() -> Vec<Case> {
    let birthday = json!({
        "template": "birthday",
        "palette": "dawn",
        "texts": {
            "title": "Doğum günün kutlu olsun, Ayşe Yılmaz!",
            "age_line": "36 yaşına giriyor",
            "date_line": "3 Ekim"
        },
        "initials": "AY"
    });
    let number_name = json!({
        "template": "contact",
        "palette": "ink",
        "texts": {"title": "+90 555 000 12 34", "numbers": "+90 555 000 12 34"},
        "photo": "$DIR/junk.jpg",
        "initials": "+9",
        "qr": "BEGIN:VCARD\nVERSION:3.0\nTEL:+90 555 000 12 34\nEND:VCARD\n"
    });
    let mut animated = birthday.clone();
    animated["animated"] = json!(true);
    vec![
        Case::new(json!({"spec": birthday, "out": "$DIR/card.png"})).seeded("cards"),
        Case::named(
            "number_name",
            json!({"spec": number_name, "out": "$DIR/contact.png"}),
        )
        .seeded("cards"),
        Case::named(
            "animated",
            json!({"spec": animated, "out": "$DIR/card.gif"}),
        )
        .seeded("cards"),
        Case::named(
            "text_missing",
            json!({"spec": {"template": "birthday", "palette": "dawn", "texts": {"title": " "}},
                   "out": "$DIR/card.png"}),
        )
        .seeded("cards"),
        Case::named(
            "out_unwritable",
            json!({"spec": birthday, "out": "$DIR/junk.jpg/card.png"}),
        )
        .seeded("cards"),
        Case::named(
            "out_invalid",
            json!({"spec": birthday, "out": "card.png"}),
        ),
    ]
}

// ---- cards.cancel

#[derive(Deserialize)]
struct CancelReq {
    out: String,
}

#[derive(Debug, Serialize)]
struct CancelAns {
    cancelled: bool,
}

fn cancel(req: CancelReq) -> Result<CancelAns, FfiError> {
    let flag = lock().get(Path::new(&req.out)).cloned();
    Ok(CancelAns {
        cancelled: flag.is_some_and(|flag| {
            flag.store(true, Ordering::Relaxed);
            true
        }),
    })
}

fn cancel_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"out": "/data/user/0/dev.rosty.fixture/cache/share/cards/none.png"}),
    )]
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn spec(animated: bool) -> CardSpec {
        serde_json::from_value(json!({
            "template": "birthday",
            "palette": "dusk",
            "animated": animated,
            "texts": {"title": "Happy birthday, Ayşe!"},
            "initials": "A"
        }))
        .expect("spec")
    }

    #[test]
    fn a_render_writes_the_card_it_answers() {
        let dir = std::env::temp_dir().join(format!("rosty-ffi-cards-{}", std::process::id()));
        let out = dir.join("a.png");
        let ans = render(RenderReq {
            spec: spec(false),
            out: out.to_string_lossy().into_owned(),
        })
        .expect("renders");
        assert_eq!((ans.width, ans.height, ans.frames), (STILL.0, STILL.1, 1));
        assert_eq!(ans.bytes, std::fs::metadata(&out).expect("file").len());
        assert!(lock().is_empty(), "the flag is gone once the render ends");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn cancel_stops_a_running_render_and_leaves_no_file() {
        let dir = std::env::temp_dir().join(format!("rosty-ffi-cards-c-{}", std::process::id()));
        let out = dir.join("moving.gif");
        let path = out.to_string_lossy().into_owned();
        let worker = {
            let path = path.clone();
            std::thread::spawn(move || {
                render(RenderReq {
                    spec: spec(true),
                    out: path,
                })
            })
        };
        // Cancel as soon as the render is registered.
        let mut cancelled = false;
        for _ in 0..2000 {
            if cancel(CancelReq { out: path.clone() })
                .expect("cancel")
                .cancelled
            {
                cancelled = true;
                break;
            }
            std::thread::sleep(Duration::from_millis(1));
        }
        assert!(cancelled, "the render was never seen running");
        let err = worker.join().expect("thread").expect_err("cancelled");
        assert_eq!((err.kind, err.message.as_str()), (ErrorKind::Invalid, "cancelled"));
        assert!(!out.exists());
        assert!(!cancel(CancelReq { out: path }).expect("cancel").cancelled);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn errors_are_codes() {
        let err = render(RenderReq {
            spec: spec(false),
            out: "relative.png".into(),
        })
        .expect_err("relative");
        assert_eq!(err.message, "out_invalid");
        let mut empty = spec(false);
        empty.texts.clear();
        let err = render(RenderReq {
            spec: empty,
            out: std::env::temp_dir()
                .join("never.png")
                .to_string_lossy()
                .into_owned(),
        })
        .expect_err("no title");
        assert_eq!(err.message, "text_missing");
        assert_eq!(err.detail, Some(json!({"key": "title"})));
    }
}
