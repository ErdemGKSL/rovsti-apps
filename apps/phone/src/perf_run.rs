//! Scripted perf scenarios (story 12.2). Compiled with the `perf` feature
//! only, so the signed release build carries none of it.
//!
//! A scenario is a list of timed [`Step`]s that go through the same code as a
//! tap (`select_tab`, `activate`, the incoming buttons), so what is measured
//! is what the owner feels. The runner logs one `RovstiPerf` report per
//! scenario when it ends (`rovsti_ui::perf::Recorder`).
//!
//! Start one on the phone with `scripts/perf-run.sh` (it sets the system
//! property `debug.rovsti.perf`), on the desktop with `ROVSTI_PERF=tab,keypad`.

#[cfg(feature = "perf")]
use std::time::{Duration, Instant};

use gpui::{Context, Window};
#[cfg(feature = "perf")]
use rovsti_ui::perf::Recorder;
use rovsti_ui::perf::{self, Scenario};

use crate::{KEYPAD_TAB, PhoneApp, RECENTS_TAB};

/// One thing the owner would do.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum Action {
    Tab(usize),
    /// Finger down on the control with this press id.
    Press(&'static str),
    /// Finger up.
    Release,
    /// Put the Recents list `y` px down.
    Scroll(f32),
    /// Swap in the 500-row call log.
    BigLog,
    /// A fake outgoing call (never reaches Telecom).
    Call,
    /// A fake incoming call.
    Ring,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Step {
    pub at_ms: u32,
    pub action: Action,
}

pub(crate) struct Script {
    #[cfg_attr(not(feature = "perf"), allow(dead_code))]
    pub scenario: Scenario,
    pub steps: Vec<Step>,
    /// The scenario ends this long after it starts.
    pub total_ms: u32,
}

const KEYS: [&str; 12] = [
    "key-5", "key-5", "key-5", "key-1", "key-2", "key-3", "key-4", "key-6", "key-7", "key-8",
    "key-9", "key-0",
];

fn at(at_ms: u32, action: Action) -> Step {
    Step { at_ms, action }
}

/// The scenarios by name, or `None` for an unknown one.
pub(crate) fn script(name: &str) -> Option<Script> {
    let mut steps = Vec::new();
    let (scenario, total_ms) = match name {
        // 20 ping-pongs between Recents and the Keypad, a quick tap rhythm.
        "tab" => {
            steps.push(at(0, Action::Tab(KEYPAD_TAB)));
            for i in 0..40u32 {
                let tab = if i % 2 == 0 { RECENTS_TAB } else { KEYPAD_TAB };
                steps.push(at(800 + i * 450, Action::Tab(tab)));
            }
            (Scenario::Tab, 800 + 40 * 450 + 900)
        }
        // 500 rows fast down, then back up.
        "recents" => {
            steps.push(at(0, Action::Tab(RECENTS_TAB)));
            steps.push(at(100, Action::BigLog));
            let (start, frame, frames) = (1500u32, 16u32, 312u32);
            let far = 500.0 * 58.0 - 700.0;
            for i in 0..=frames * 2 {
                let t = if i <= frames {
                    i as f32 / frames as f32
                } else {
                    1.0 - (i - frames) as f32 / frames as f32
                };
                // Ease in and out like a fling that is caught.
                let eased = t * t * (3.0 - 2.0 * t);
                steps.push(at(start + i * frame, Action::Scroll(far * eased)));
            }
            (Scenario::Recents, start + frames * 2 * frame + 600)
        }
        // Six keys a second for ten seconds, with the odd Delete.
        "keypad" => {
            steps.push(at(0, Action::Tab(KEYPAD_TAB)));
            for i in 0..60u32 {
                let t = 800 + i * 166;
                let key = if i % 8 == 7 { "delete" } else { key_for(i) };
                steps.push(at(t, Action::Press(key)));
                steps.push(at(t + 110, Action::Release));
            }
            (Scenario::Keypad, 800 + 60 * 166 + 800)
        }
        // A call, its controls, the timer.
        "incall" => {
            steps.push(at(0, Action::Tab(KEYPAD_TAB)));
            steps.push(at(300, Action::Call));
            for (i, id) in ["ic-mute", "ic-hold", "ic-hold", "ic-speaker", "ic-mute"]
                .into_iter()
                .enumerate()
            {
                let t = 3200 + i as u32 * 900;
                steps.push(at(t, Action::Press(id)));
                steps.push(at(t + 250, Action::Release));
            }
            steps.push(at(8200, Action::Press("ic-end")));
            steps.push(at(8450, Action::Release));
            (Scenario::InCall, 10_500)
        }
        // Entrance and rings, declined once and answered once.
        "incoming" => {
            steps.push(at(0, Action::Tab(KEYPAD_TAB)));
            steps.push(at(500, Action::Ring));
            steps.push(at(5000, Action::Press("in-decline")));
            steps.push(at(5250, Action::Release));
            steps.push(at(7000, Action::Ring));
            steps.push(at(11_500, Action::Press("in-answer")));
            steps.push(at(11_750, Action::Release));
            steps.push(at(14_500, Action::Press("ic-end")));
            steps.push(at(14_750, Action::Release));
            (Scenario::Incoming, 16_500)
        }
        // Nothing happens; no frame may be produced.
        "idle" => (Scenario::Idle, 30_000),
        _ => return None,
    };
    Some(Script {
        scenario,
        steps,
        total_ms,
    })
}

fn key_for(i: u32) -> &'static str {
    KEYS[(i as usize) % KEYS.len()]
}

/// The names a trigger asks for: `all`, or comma-separated scenarios, each
/// optionally followed by `:<run id>` (a changing id re-runs the same ask).
pub(crate) fn parse_trigger(value: &str) -> Vec<&str> {
    let mut out = Vec::new();
    for part in value.split(',') {
        let name = part.split(':').next().unwrap_or("").trim();
        match name {
            "" => {}
            "all" => out.extend(["tab", "recents", "keypad", "incall", "incoming", "idle"]),
            other => out.push(other),
        }
    }
    out
}

impl PhoneApp {
    #[cfg(feature = "perf")]
    /// Switches the diagnostics on and starts watching for a trigger.
    pub(crate) fn start_perf_runner(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        Recorder::enable(window);
        cx.spawn_in(window, async move |this, cx| {
            let mut seen = String::new();
            loop {
                let value = trigger_value();
                if !value.is_empty() && value != seen {
                    seen = value.clone();
                    for name in parse_trigger(&value) {
                        run_scenario(&this, cx, name).await;
                    }
                }
                cx.background_executor().timer(Duration::from_secs(1)).await;
            }
        })
        .detach();
    }

    /// Called from every render: feeds the recorder.
    pub(crate) fn perf_observe(&mut self, window: &Window) {
        let scenario = perf::forced().unwrap_or_else(|| self.scenario());
        self.recorder.observe(
            window,
            scenario,
            self.motion.requested(),
            self.motion.interval(),
        );
    }

    #[cfg(feature = "perf")]
    fn perf_begin(&mut self, scenario: Scenario) {
        self.recorder.finish_always();
        self.recorder.set_scenario(scenario);
        perf::force_scenario(Some(scenario));
    }

    #[cfg(feature = "perf")]
    fn perf_end(&mut self) {
        self.recorder.finish_always();
        perf::force_scenario(None);
    }

    pub(crate) fn perf_apply(
        &mut self,
        action: Action,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        match action {
            Action::Tab(tab) => self.select_tab(tab, cx),
            Action::Press(id) => {
                self.presses.down(id);
                if matches!(id, "in-answer" | "in-decline") {
                    self.incoming_mouse_down(id, window, cx);
                } else {
                    self.activate(id, window, cx);
                }
            }
            Action::Release => {
                self.released(cx);
                self.tabs.release();
            }
            Action::Scroll(y) => self.recents.perf_scroll(y),
            Action::BigLog => self.backend.update(cx, |b, cx| b.perf_big_log(cx)),
            Action::Call => self.backend.update(cx, |b, cx| b.perf_call(cx)),
            Action::Ring => self.backend.update(cx, |b, cx| {
                b.preview_ring(Some("Ayse Yilmaz"), false, false, cx)
            }),
        }
        cx.notify();
    }
}

#[cfg(feature = "perf")]
fn trigger_value() -> String {
    match rovsti_android::perf::trigger() {
        Ok(value) => value,
        Err(_) => std::env::var("ROVSTI_PERF").unwrap_or_default(),
    }
}

#[cfg(feature = "perf")]
async fn run_scenario(
    this: &gpui::WeakEntity<PhoneApp>,
    cx: &mut gpui::AsyncWindowContext,
    name: &str,
) {
    let Some(script) = script(name) else {
        log::warn!(target: "RovstiPerf", "unknown scenario {name}");
        return;
    };
    if this
        .update(cx, |this, _| this.perf_begin(script.scenario))
        .is_err()
    {
        return;
    }
    let start = Instant::now();
    for step in &script.steps {
        let due = Duration::from_millis(u64::from(step.at_ms));
        if let Some(wait) = due.checked_sub(start.elapsed()) {
            cx.background_executor().timer(wait).await;
        }
        if this
            .update_in(cx, |this, window, cx| {
                this.perf_apply(step.action, window, cx)
            })
            .is_err()
        {
            return;
        }
    }
    let end = Duration::from_millis(u64::from(script.total_ms));
    if let Some(wait) = end.checked_sub(start.elapsed()) {
        cx.background_executor().timer(wait).await;
    }
    this.update(cx, |this, _| this.perf_end()).ok();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_scenario_has_a_script_in_time_order() {
        for name in ["tab", "recents", "keypad", "incall", "incoming", "idle"] {
            let script = script(name).expect(name);
            let mut last = 0;
            for step in &script.steps {
                assert!(step.at_ms >= last, "{name} out of order");
                last = step.at_ms;
            }
            assert!(script.total_ms >= last, "{name} ends before its last step");
        }
        assert!(script("nope").is_none());
    }

    #[test]
    fn tab_is_twenty_ping_pongs() {
        let tabs = script("tab").map(|s| {
            s.steps
                .iter()
                .filter(|s| matches!(s.action, Action::Tab(_)))
                .count()
        });
        assert_eq!(tabs, Some(41), "one setup tab and 40 switches");
    }

    #[test]
    fn keypad_presses_six_keys_a_second() {
        let s = script("keypad").expect("keypad");
        let presses = s
            .steps
            .iter()
            .filter(|s| matches!(s.action, Action::Press(_)))
            .count();
        assert_eq!(presses, 60);
        assert!((10_000..12_000).contains(&s.total_ms));
    }

    #[test]
    fn triggers_parse() {
        assert_eq!(parse_trigger("tab:3"), ["tab"]);
        assert_eq!(parse_trigger("keypad, incall:9"), ["keypad", "incall"]);
        assert_eq!(parse_trigger("all").len(), 6);
        assert!(parse_trigger("").is_empty());
    }
}
