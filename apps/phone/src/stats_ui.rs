//! Call statistics in the UI (U7, U18): the "Call statistics" block under
//! Recent calls in the Number and Contact sheets, and the Settings page.
//!
//! The numbers come from `rovsti_core::call_stats`. The merged
//! `Vec<StatCall>` is cached against the call log snapshot and the
//! WhatsApp history revision, and each view-model is cached against that and
//! the numbers it describes, so nothing is recomputed per frame.

use std::{cell::RefCell, sync::Arc};

use gpui::prelude::*;
use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, MouseButton, ParentElement, Role,
    SharedString, StatefulInteractiveElement, Styled, div, px, svg,
};
use rovsti_core::call_stats::{self, DayBar, Range, StatCall, Totals};
use rovsti_ui::{
    Icon, theme,
    tokens::{Theme, mix, typography},
    touch::touch_surface,
};

use crate::{
    PhoneApp,
    backend::{self, CallLog, Load},
    fx::A_STAT_BAR,
};

/// Share button ids of the two sheets.
pub(crate) const NUMBER_SHARE: &str = "sheet-nst-share";
pub(crate) const CONTACT_SHARE: &str = "sheet-cst-share";
/// Settings page ids (the page's prefix is `Sub::Stats.prefix()`).
pub(crate) const SEGMENT_PREFIX: &str = "set-st-seg-";
pub(crate) const PERSON_PREFIX: &str = "set-st-p-";
/// Id prefix of a chart bar (`st-bar-{i}`). Only the page chart's bars with
/// calls are buttons; the person-sheet chart stays inert.
pub(crate) const BAR_PREFIX: &str = "st-bar-";
/// Row id prefix and Close id of the bucket sheet (`SheetKind::StatsBucket`).
/// `sheet-sb-` shares no prefix with `sheet-sd-`, `sheet-sim`, `sheet-stats`.
pub(crate) const BUCKET_PREFIX: &str = "sheet-sb-";
pub(crate) const BUCKET_CLOSE: &str = "sheet-sb-close";
/// Row height of the bucket list.
const BUCKET_ROW_H: f32 = 56.0;
/// The "Export CSV" pill of the page.
pub(crate) const CSV_ID: &str = "set-st-csv";
/// The Share pill beside Export CSV.
pub(crate) const SHARE_ID: &str = "set-st-share";

/// A non-zero value never draws thinner than this share of the chart height.
const BAR_MIN: f32 = 0.04;
const SHEET_CHART_H: f32 = 56.0;
const PAGE_CHART_H: f32 = 120.0;
/// People listed on the page.
const TOP_PEOPLE: usize = 10;

/// The pill after an export started: how many people the file holds, or why
/// nothing was exported.
pub(crate) fn export_pill_text(people: usize) -> String {
    match people {
        0 => "No calls in this range".to_owned(),
        1 => "Exported 1 person".to_owned(),
        n => format!("Exported {n} people"),
    }
}

/// Height of a bar as a share of the chart (0 to 1).
pub(crate) fn bar_frac(v: u64, max: u64) -> f32 {
    if v == 0 || max == 0 {
        return 0.0;
    }
    ((v as f64 / max as f64) as f32).clamp(BAR_MIN, 1.0)
}

/// One drawn bar.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct BarVm {
    pub label: SharedString,
    pub frac: f32,
    pub today: bool,
    pub aria: SharedString,
    /// Calls in the bucket; a bar with none is not tappable.
    pub calls: u32,
}

/// The bar index in a `st-bar-{i}` id.
pub(crate) fn bar_index(id: &str) -> Option<usize> {
    id.strip_prefix(BAR_PREFIX)?.parse().ok()
}

/// The bucket row index in a `sheet-sb-{i}` id (`None` for Close).
pub(crate) fn bucket_row_index(id: &str) -> Option<usize> {
    id.strip_prefix(BUCKET_PREFIX)?.parse().ok()
}

/// Whether a bar reacts to a tap: only on the page chart, and only with calls.
pub(crate) fn bar_tappable(bar: &BarVm, page: bool) -> bool {
    page && bar.calls > 0
}

pub(crate) fn bar_vms(bars: &[DayBar]) -> Vec<BarVm> {
    let max = bars.iter().map(|b| b.talk_s).max().unwrap_or(0);
    let last = bars.len().saturating_sub(1);
    bars.iter()
        .enumerate()
        .map(|(i, b)| BarVm {
            label: b.label.clone().into(),
            frac: bar_frac(b.talk_s, max),
            today: i == last,
            calls: b.calls,
            aria: format!(
                "{}: {}, {}",
                b.label,
                call_stats::talk_text(b.talk_s),
                if b.calls == 1 {
                    "1 call".to_owned()
                } else {
                    format!("{} calls", b.calls)
                }
            )
            .into(),
        })
        .collect()
}

/// What a sheet's statistics block shows.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct StatsVm {
    pub summary: SharedString,
    pub incoming: u32,
    pub outgoing: u32,
    pub missed: u32,
    pub longest: SharedString,
    pub bars: Vec<BarVm>,
    /// The text the Share button sends.
    pub share: String,
}

/// The block for `numbers`, or `None` when they have no calls.
pub(crate) fn block_vm(
    calls: &[StatCall],
    numbers: &[&str],
    name: &str,
    now_ms: i64,
    tz_offset_s: i32,
) -> Option<StatsVm> {
    let mine = call_stats::calls_for_numbers(calls, numbers);
    let all = call_stats::totals_since(&mine, i64::MIN);
    if all.calls == 0 {
        return None;
    }
    let week = call_stats::totals_since(&mine, Range::Week.since_ms(now_ms, tz_offset_s));
    Some(StatsVm {
        summary: call_stats::summary_line(&all).into(),
        incoming: all.incoming,
        outgoing: all.outgoing,
        missed: all.missed,
        longest: call_stats::talk_text(all.longest_s).into(),
        bars: bar_vms(&call_stats::last_days(&mine, now_ms, tz_offset_s, 7)),
        share: call_stats::share_text(name, &all, &week),
    })
}

/// A person on the Settings page.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PersonVm {
    pub number: SharedString,
    pub title: SharedString,
    pub detail: SharedString,
    pub named: bool,
}

/// The Settings page for one range.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct PageVm {
    pub summary: SharedString,
    pub counts: Totals,
    pub bars: Vec<BarVm>,
    pub people: Vec<PersonVm>,
}

pub(crate) fn page_vm(calls: &[StatCall], range: Range, now_ms: i64, tz_offset_s: i32) -> PageVm {
    let since = range.since_ms(now_ms, tz_offset_s);
    let totals = call_stats::totals_since(calls, since);
    PageVm {
        summary: call_stats::summary_line(&totals).into(),
        counts: totals,
        bars: bar_vms(&range.bars(calls, now_ms, tz_offset_s)),
        people: call_stats::top_people(calls, since, TOP_PEOPLE)
            .into_iter()
            .map(|p| PersonVm {
                title: p.name.clone().unwrap_or_else(|| p.number.clone()).into(),
                named: p.name.is_some(),
                detail: format!(
                    "{} · {}",
                    call_stats::talk_text(p.totals.talk_s),
                    if p.totals.calls == 1 {
                        "1 call".to_owned()
                    } else {
                        format!("{} calls", p.totals.calls)
                    }
                )
                .into(),
                number: p.number.into(),
            })
            .collect(),
    }
}

struct Merged {
    log: Arc<CallLog>,
    rev: u64,
    calls: Arc<Vec<StatCall>>,
}

type BlockKey = (usize, u64, String);
type PageKey = (usize, u64, Range);

/// The page's state and caches.
#[derive(Default)]
pub(crate) struct StatsUi {
    pub range: Range,
    /// The bar whose calls the bucket sheet lists; read again on every frame
    /// so a log refresh rebuilds the list.
    bucket: Option<usize>,
    merged: RefCell<Option<Merged>>,
    block: RefCell<Option<(BlockKey, Option<Arc<StatsVm>>)>>,
    page: RefCell<Option<(PageKey, Arc<PageVm>)>>,
}

impl PhoneApp {
    /// The merged call list and the clock offset; rebuilt only when the log
    /// snapshot or the WhatsApp history changed.
    fn stat_calls(&self, cx: &gpui::App) -> Option<(Arc<Vec<StatCall>>, i32, usize, u64)> {
        let Load::Ready(log) = self.backend.read(cx).call_log() else {
            return None;
        };
        let rev = self.wa_call.read(cx).history_rev();
        let mut merged = self.stats.merged.borrow_mut();
        let fresh = merged
            .as_ref()
            .is_some_and(|m| Arc::ptr_eq(&m.log, &log) && m.rev == rev);
        if !fresh {
            let extras = self.wa_call.read(cx).recents_extras();
            *merged = Some(Merged {
                calls: Arc::new(call_stats::from_sources(&log.entries, &extras)),
                log: Arc::clone(&log),
                rev,
            });
        }
        let m = merged.as_ref()?;
        Some((
            Arc::clone(&m.calls),
            log.locale.tz_offset_s,
            Arc::as_ptr(&m.log) as usize,
            rev,
        ))
    }

    /// The statistics block of a sheet for `numbers`; cached per snapshot.
    pub(crate) fn stats_block_vm(
        &self,
        numbers: &[&str],
        name: &str,
        cx: &gpui::App,
    ) -> Option<Arc<StatsVm>> {
        let (calls, tz, ptr, rev) = self.stat_calls(cx)?;
        let key: BlockKey = (ptr, rev, format!("{name}|{}", numbers.join(",")));
        if let Some((k, vm)) = self.stats.block.borrow().as_ref()
            && *k == key
        {
            return vm.clone();
        }
        let vm = block_vm(&calls, numbers, name, backend::now_ms(), tz).map(Arc::new);
        *self.stats.block.borrow_mut() = Some((key, vm.clone()));
        vm
    }

    /// The Settings page's view-model; cached per snapshot and range.
    pub(crate) fn stats_page_vm(&self, cx: &gpui::App) -> Option<Arc<PageVm>> {
        let (calls, tz, ptr, rev) = self.stat_calls(cx)?;
        let key: PageKey = (ptr, rev, self.stats.range);
        if let Some((k, vm)) = self.stats.page.borrow().as_ref()
            && *k == key
        {
            return Some(Arc::clone(vm));
        }
        let vm = Arc::new(page_vm(&calls, self.stats.range, backend::now_ms(), tz));
        *self.stats.page.borrow_mut() = Some((key, Arc::clone(&vm)));
        Some(vm)
    }

    /// The Share button of a sheet's block.
    pub(crate) fn stats_share(&mut self, numbers: &[String], name: &str, cx: &mut Context<Self>) {
        let refs: Vec<&str> = numbers.iter().map(String::as_str).collect();
        let Some(vm) = self.stats_block_vm(&refs, name, cx) else {
            self.show_notice_pill("No calls to share", None, false, cx);
            return;
        };
        let outcome = backend::share_text(&vm.share);
        if outcome != backend::Outcome::Done {
            self.show_notice_pill(
                outcome.why_not("Couldn't open the share sheet"),
                None,
                false,
                cx,
            );
        }
    }

    /// A control of the Settings page was pressed.
    pub(crate) fn stats_tap(
        &mut self,
        id: &str,
        window: &mut gpui::Window,
        cx: &mut Context<Self>,
    ) {
        if let Some(i) = bar_index(id) {
            self.open_bucket(i, window, cx);
        } else if id == CSV_ID {
            self.stats_export_csv(window, cx);
        } else if id == SHARE_ID {
            self.stats_share_range(window, cx);
        } else if let Some(i) = id
            .strip_prefix(SEGMENT_PREFIX)
            .and_then(|i| i.parse::<usize>().ok())
        {
            if let Some(range) = Range::ALL.get(i) {
                self.stats.range = *range;
            }
        } else if let Some(i) = id
            .strip_prefix(PERSON_PREFIX)
            .and_then(|i| i.parse::<usize>().ok())
            && let Some(person) = self
                .stats_page_vm(cx)
                .and_then(|vm| vm.people.get(i).cloned())
        {
            self.open_number_sheet_for(person.number, person.title, person.named, cx);
        }
        cx.notify();
    }

    /// Shares the summary text of the chosen range.
    fn stats_share_range(&mut self, window: &mut gpui::Window, cx: &mut Context<Self>) {
        let Some((calls, tz, _, _)) = self.stat_calls(cx) else {
            self.show_notice_pill("Call history isn't loaded yet", None, false, cx);
            return;
        };
        let range = self.stats.range;
        let people =
            call_stats::top_people(&calls, range.since_ms(backend::now_ms(), tz), usize::MAX);
        let text = call_stats::range_share_text(&people, range);
        if text.is_empty() {
            self.show_notice_pill(export_pill_text(0), None, false, cx);
            return;
        }
        rovsti_ui::haptics::perform(window, gpui::HapticFeedback::Selection);
        let outcome = backend::share_text(&text);
        if outcome != backend::Outcome::Done {
            self.show_notice_pill(
                outcome.why_not("Couldn't open the share sheet"),
                None,
                false,
                cx,
            );
        }
    }

    /// Builds the per-person CSV of the chosen range and opens "save as".
    fn stats_export_csv(&mut self, window: &mut gpui::Window, cx: &mut Context<Self>) {
        let Some((calls, tz, _, _)) = self.stat_calls(cx) else {
            self.show_notice_pill("Call history isn't loaded yet", None, false, cx);
            return;
        };
        let range = self.stats.range;
        let now = backend::now_ms();
        let people = call_stats::top_people(&calls, range.since_ms(now, tz), usize::MAX);
        if people.is_empty() {
            self.show_notice_pill(export_pill_text(0), None, false, cx);
            return;
        }
        let csv = call_stats::people_csv(&people, range);
        self.backup.export_kind = crate::backup_ui::ExportKind::Stats;
        let outcome = backend::export_calls(&call_stats::stats_csv_file_name(range, now, tz), &csv);
        if outcome.failed() || outcome == backend::Outcome::NotAvailable {
            self.show_notice_pill(outcome.why_not("Couldn't export"), None, false, cx);
        } else {
            // The picker's result pill says "Call statistics exported"; opening
            // a system sheet is a Selection tick, like the other exports.
            rovsti_ui::haptics::perform(window, gpui::HapticFeedback::Selection);
        }
    }

    /// Opens the Number sheet of `number` (from a list that is not Recents).
    pub(crate) fn open_number_sheet_for(
        &mut self,
        number: SharedString,
        title: SharedString,
        named: bool,
        cx: &mut Context<Self>,
    ) {
        if !self.can_open_sheet() || self.sheet.is_visible() {
            return;
        }
        self.show_number_sheet(number, title, named, cx);
    }

    /// Puts the Number sheet of `number` on screen, over whatever sheet is open.
    fn show_number_sheet(
        &mut self,
        number: SharedString,
        title: SharedString,
        named: bool,
        cx: &gpui::App,
    ) {
        self.recents.number_history = self.history_for(&[number.as_ref()], cx);
        self.recents.number_history_edit = false;
        self.recents.number_blocked = false;
        self.recents.number = Some(crate::recents::RowVm::for_number(number, title, named));
        self.setup_note = None;
        self.sheet_kind = crate::SheetKind::Number;
        self.sheet.open();
    }

    /// The bar `index` of the chosen range and its calls, newest first.
    fn bucket_calls(
        &self,
        index: usize,
        cx: &gpui::App,
    ) -> Option<(DayBar, Vec<StatCall>, i32, bool)> {
        let (calls, tz, _, _) = self.stat_calls(cx)?;
        let hour12 = match self.backend.read(cx).call_log() {
            Load::Ready(log) => log.locale.format.hour12,
            _ => false,
        };
        let bar = self
            .stats
            .range
            .bars(&calls, backend::now_ms(), tz)
            .into_iter()
            .nth(index)?;
        let list = call_stats::calls_in(&calls, bar.day_start_ms, bar.end_ms);
        Some((bar, list, tz, hour12))
    }

    /// A tap on a chart bar: lists that day's or week's calls.
    fn open_bucket(&mut self, index: usize, window: &mut gpui::Window, cx: &mut Context<Self>) {
        if !self.can_open_sheet() || self.sheet.is_visible() {
            return;
        }
        let has_calls = self
            .bucket_calls(index, cx)
            .is_some_and(|(bar, _, _, _)| bar.calls > 0);
        if !has_calls {
            return;
        }
        rovsti_ui::haptics::perform(window, gpui::HapticFeedback::Selection);
        self.stats.bucket = Some(index);
        self.sheet_kind = crate::SheetKind::StatsBucket;
        self.sheet.open();
        self.presses.release_all();
        cx.notify();
    }

    /// A tap on a bucket row or Close.
    pub(crate) fn bucket_act(&mut self, id: &str, cx: &mut Context<Self>) {
        if id == BUCKET_CLOSE {
            self.close_sheet(cx);
            return;
        }
        let Some(row) = bucket_row_index(id) else {
            return;
        };
        let Some(index) = self.stats.bucket else {
            return;
        };
        let Some((_, list, tz, hour12)) = self.bucket_calls(index, cx) else {
            return;
        };
        let Some(call) = list.get(row) else {
            return;
        };
        let (title, _) = call_stats::call_line(call, tz, hour12);
        let named = call.name.as_deref().is_some_and(|n| !n.trim().is_empty());
        self.presses.release_all();
        self.show_number_sheet(call.number.clone().into(), title.into(), named, cx);
        cx.notify();
    }

    /// The bucket sheet: title, summary, one row per call, Close.
    pub(crate) fn stats_bucket_sheet(
        &self,
        theme: Theme,
        window: &gpui::Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        let colors = theme::colors(theme);
        let (bar, list, tz, hour12) = self.bucket_calls(self.stats.bucket?, cx)?;
        let mut rows = div()
            .id("sheet-sb-list")
            .flex()
            .flex_col()
            .max_h(px(BUCKET_ROW_H * 6.5))
            .overflow_y_scroll();
        for (i, call) in list.iter().enumerate() {
            let id: SharedString = format!("{BUCKET_PREFIX}{i}").into();
            let press = self.presses.value(&id).clamp(0.0, 1.0);
            let (title, sub) = call_stats::call_line(call, tz, hour12);
            let missed = call.dir == call_stats::StatDir::Missed;
            let act = id.clone();
            rows = rows.child(
                div()
                    .id(id.clone())
                    .role(Role::Button)
                    .aria_label(format!("{title}, {sub}"))
                    .relative()
                    .w_full()
                    .min_h(px(BUCKET_ROW_H))
                    .px(px(20.0))
                    .py(px(8.0))
                    .flex()
                    .flex_col()
                    .justify_center()
                    .bg(gpui::Hsla {
                        a: 0.9 * press,
                        ..colors.fill
                    })
                    .child(
                        div()
                            .truncate()
                            .text_size(typography::SUBHEADLINE.size)
                            .text_color(colors.label)
                            .child(title),
                    )
                    .child(
                        div()
                            .truncate()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(if missed {
                                colors.red
                            } else {
                                colors.secondary_label
                            })
                            .child(sub),
                    )
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, _, _, cx| {
                            this.presses.down(act.clone());
                            this.bucket_act(&act, cx);
                        }),
                    )
                    .child(touch_surface(PhoneApp::touch_listener(cx, Some(id)))),
            );
        }
        let close: SharedString = BUCKET_CLOSE.into();
        let press = self.presses.value(&close).clamp(0.0, 1.0);
        let body = div()
            .flex()
            .flex_col()
            .pb(px(16.0))
            .child(
                div()
                    .px(px(20.0))
                    .pb(px(8.0))
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .text_size(typography::HEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(colors.label)
                            .child(call_stats::bucket_title(&bar, self.stats.range, tz)),
                    )
                    .child(
                        div()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child(call_stats::bucket_summary(&list)),
                    ),
            )
            .child(rows)
            .child(
                div()
                    .id(close.clone())
                    .role(Role::Button)
                    .aria_label("Close")
                    .relative()
                    .w_full()
                    .h(px(BUCKET_ROW_H))
                    .flex()
                    .items_center()
                    .justify_center()
                    .bg(gpui::Hsla {
                        a: 0.9 * press,
                        ..colors.fill
                    })
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(colors.accent)
                    .child("Close")
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, _, _, cx| {
                            this.presses.down(BUCKET_CLOSE);
                            this.bucket_act(BUCKET_CLOSE, cx);
                        }),
                    )
                    .child(touch_surface(PhoneApp::touch_listener(cx, Some(close)))),
            )
            .into_any_element();
        self.modal_sheet(theme, window, "Calls", body, cx)
    }

    fn bar_chart(
        &self,
        theme: Theme,
        bars: &[BarVm],
        height: f32,
        marks: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .flex()
            .gap(px(6.0))
            .w_full()
            .children(bars.iter().enumerate().map(|(i, bar)| {
                let mut cell = div()
                    .relative()
                    .h(px(height))
                    .w_full()
                    .flex()
                    .flex_col()
                    .justify_end();
                if marks {
                    cell = cell.child(self.marks.probe(A_STAT_BAR + i));
                }
                let id = SharedString::from(format!("{BAR_PREFIX}{i}"));
                let tappable = bar_tappable(bar, marks);
                let press = if tappable {
                    self.presses.value(&id).clamp(0.0, 1.0)
                } else {
                    0.0
                };
                let mut column = div()
                    .id(id.clone())
                    .role(if tappable { Role::Button } else { Role::Row })
                    .aria_label(bar.aria.clone())
                    .relative()
                    .opacity(1.0 - 0.3 * press)
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .gap(px(4.0))
                    .child(
                        cell.child(div().w_full().h(px(height * bar.frac)).rounded(px(4.0)).bg(
                            gpui::Hsla {
                                a: if bar.today { 1.0 } else { 0.55 },
                                ..colors.accent
                            },
                        )),
                    )
                    .child(
                        div()
                            .w_full()
                            .flex()
                            .justify_center()
                            .text_size(typography::CAPTION.size)
                            .text_color(if bar.today {
                                colors.label
                            } else {
                                colors.secondary_label
                            })
                            .child(bar.label.clone()),
                    );
                if tappable {
                    let act = id.clone();
                    column = column
                        .on_mouse_down(
                            MouseButton::Left,
                            cx.listener(move |this, _, window, cx| {
                                this.presses.down(act.clone());
                                this.settings_tap(&act, window, cx);
                            }),
                        )
                        .child(touch_surface(PhoneApp::touch_listener(cx, Some(id))));
                }
                column
            }))
            .into_any_element()
    }

    fn count_cell(theme: Theme, value: u32, caption: &'static str) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .flex_1()
            .flex()
            .flex_col()
            .items_center()
            .child(
                div()
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(if caption == "Missed" && value > 0 {
                        theme::call_colors(theme).danger
                    } else {
                        colors.label
                    })
                    .child(value.to_string()),
            )
            .child(
                div()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child(caption),
            )
            .into_any_element()
    }

    /// The "Call statistics" block of a sheet; `None` without calls.
    pub(crate) fn stats_section(
        &self,
        theme: Theme,
        vm: &StatsVm,
        share_id: &'static str,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let press = self.presses.value(share_id).clamp(0.0, 1.0);
        let share = div()
            .id(share_id)
            .role(Role::Button)
            .aria_label("Share call statistics")
            .relative()
            .w_full()
            .h(px(44.0))
            .rounded_full()
            .bg(mix(colors.fill, gpui::white(), 0.16 * press))
            .flex()
            .items_center()
            .justify_center()
            .gap(px(8.0))
            .text_size(typography::SUBHEADLINE.size)
            .font_weight(typography::HEADLINE.weight)
            .text_color(colors.label)
            .child(
                svg()
                    .path(Icon::LucideShare2.path())
                    .size(px(18.0))
                    .text_color(colors.label),
            )
            .child("Share")
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, window, cx| {
                    this.presses.down(share_id);
                    if share_id == CONTACT_SHARE {
                        this.contact_sheet_act(share_id, window, cx);
                    } else {
                        this.number_sheet_act(share_id, window, cx);
                    }
                }),
            )
            .child(touch_surface(PhoneApp::touch_listener(
                cx,
                Some(share_id.into()),
            )));
        div()
            .flex()
            .flex_col()
            .pt(px(6.0))
            .child(
                div()
                    .px(px(16.0))
                    .pb(px(4.0))
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child("Call statistics"),
            )
            .child(
                div()
                    .id("sheet-stats")
                    .role(Role::Group)
                    .aria_label(vm.summary.clone())
                    .flex()
                    .flex_col()
                    .gap(px(10.0))
                    .p(px(16.0))
                    .rounded(px(22.0))
                    .bg(gpui::Hsla {
                        a: 0.5,
                        ..colors.fill
                    })
                    .child(
                        div()
                            .text_size(typography::BODY.size)
                            .text_color(colors.label)
                            .child(vm.summary.clone()),
                    )
                    .child(
                        div()
                            .flex()
                            .child(Self::count_cell(theme, vm.incoming, "In"))
                            .child(Self::count_cell(theme, vm.outgoing, "Out"))
                            .child(Self::count_cell(theme, vm.missed, "Missed")),
                    )
                    .child(
                        div()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child(format!("Longest call: {}", vm.longest)),
                    )
                    .child(self.bar_chart(theme, &vm.bars, SHEET_CHART_H, false, cx))
                    .child(share),
            )
            .into_any_element()
    }

    /// The Settings > Call statistics page.
    pub(crate) fn stats_body(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let vm = self.stats_page_vm(cx);
        let selected = Range::ALL
            .iter()
            .position(|r| *r == self.stats.range)
            .unwrap_or(0);
        let segments = div()
            .mx(px(16.0))
            .h(px(32.0))
            .flex()
            .rounded_full()
            .bg(gpui::Hsla {
                a: 0.55,
                ..colors.fill
            })
            .children(Range::ALL.iter().enumerate().map(|(i, range)| {
                let id = SharedString::from(format!("{SEGMENT_PREFIX}{i}"));
                div()
                    .id(id.clone())
                    .role(Role::Tab)
                    .aria_selected(i == selected)
                    .aria_label(range.label())
                    .flex_1()
                    .m(px(2.0))
                    .flex()
                    .items_center()
                    .justify_center()
                    .rounded_full()
                    .text_size(px(13.0))
                    .font_weight(if i == selected {
                        gpui::FontWeight::SEMIBOLD
                    } else {
                        gpui::FontWeight::NORMAL
                    })
                    .text_color(colors.label)
                    .when(i == selected, |d| d.bg(colors.background))
                    .child(range.label())
                    .on_mouse_down(MouseButton::Left, {
                        let id = id.clone();
                        cx.listener(move |this, _, window, cx| {
                            this.presses.down(id.clone());
                            this.settings_tap(&id, window, cx);
                        })
                    })
                    .child(touch_surface(PhoneApp::touch_listener(cx, Some(id))))
            }));
        let mut page = div()
            .id("st-scroll")
            .flex_1()
            .min_h_0()
            .overflow_y_scroll()
            .pb(px(48.0))
            .flex()
            .flex_col()
            .gap(px(14.0))
            .child(segments);
        let Some(vm) = vm else {
            return page
                .child(note_line(theme, "Call history isn't loaded yet"))
                .into_any_element();
        };
        page = page
            .child(
                div()
                    .px(px(20.0))
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child(vm.summary.clone()),
            )
            .child(
                div()
                    .mx(px(16.0))
                    .flex()
                    .child(Self::count_cell(theme, vm.counts.incoming, "In"))
                    .child(Self::count_cell(theme, vm.counts.outgoing, "Out"))
                    .child(Self::count_cell(theme, vm.counts.missed, "Missed")),
            )
            .child(div().mx(px(20.0)).child(self.bar_chart(
                theme,
                &vm.bars,
                PAGE_CHART_H,
                true,
                cx,
            )));
        if vm.counts.calls == 0 {
            return page
                .child(note_line(theme, "No calls yet"))
                .into_any_element();
        }
        let n = vm.people.len();
        page.child(
            div()
                .px(px(20.0))
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.secondary_label)
                .child("Most time on calls"),
        )
        .child(
            div()
                .mx(px(16.0))
                .rounded(px(22.0))
                .overflow_hidden()
                .bg(gpui::Hsla {
                    a: 0.72,
                    ..colors.secondary_background
                })
                .flex()
                .flex_col()
                .children(vm.people.iter().enumerate().map(|(i, p)| {
                    let id = SharedString::from(format!("{PERSON_PREFIX}{i}"));
                    let press = self.presses.value(&id).clamp(0.0, 1.0);
                    div()
                        .id(id.clone())
                        .role(Role::Button)
                        .aria_label(SharedString::from(format!("{}, {}", p.title, p.detail)))
                        .relative()
                        .w_full()
                        .min_h(px(52.0))
                        .px(px(16.0))
                        .flex()
                        .items_center()
                        .gap(px(12.0))
                        .when(i + 1 < n, |d| {
                            d.border_b_1().border_color(gpui::Hsla {
                                a: 0.25,
                                ..colors.secondary_label
                            })
                        })
                        .bg(gpui::Hsla {
                            a: 0.07 * press,
                            ..colors.label
                        })
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .truncate()
                                .text_size(typography::BODY.size)
                                .text_color(colors.label)
                                .child(p.title.clone()),
                        )
                        .child(
                            div()
                                .flex_none()
                                .text_size(typography::FOOTNOTE.size)
                                .text_color(colors.secondary_label)
                                .child(p.detail.clone()),
                        )
                        .on_mouse_down(MouseButton::Left, {
                            let id = id.clone();
                            cx.listener(move |this, _, window, cx| {
                                this.presses.down(id.clone());
                                this.settings_tap(&id, window, cx);
                            })
                        })
                        .child(touch_surface(PhoneApp::touch_listener(cx, Some(id))))
                })),
        )
        .child(
            div()
                .flex()
                .mx(px(16.0))
                .gap(px(8.0))
                .child(self.csv_pill(theme, cx))
                .child(self.share_pill(theme, cx)),
        )
        .into_any_element()
    }

    fn csv_pill(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        self.action_pill(theme, CSV_ID, "Export CSV", false, cx)
    }

    fn share_pill(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        self.action_pill(theme, SHARE_ID, "Share", true, cx)
    }

    fn action_pill(
        &self,
        theme: Theme,
        id: &'static str,
        label: &'static str,
        icon: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let press = self.presses.value(id).clamp(0.0, 1.0);
        let mut pill = div()
            .id(id)
            .role(Role::Button)
            .aria_label(label)
            .relative()
            .flex_1()
            .h(px(44.0))
            .rounded_full()
            .bg(mix(colors.fill, gpui::white(), 0.16 * press))
            .flex()
            .items_center()
            .justify_center()
            .gap(px(8.0))
            .text_size(typography::SUBHEADLINE.size)
            .font_weight(typography::HEADLINE.weight)
            .text_color(colors.label);
        if icon {
            pill = pill.child(
                svg()
                    .path(Icon::LucideShare2.path())
                    .size(px(18.0))
                    .text_color(colors.label),
            );
        }
        pill.child(label)
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, window, cx| {
                    this.presses.down(id);
                    this.settings_tap(id, window, cx);
                }),
            )
            .child(touch_surface(PhoneApp::touch_listener(cx, Some(id.into()))))
            .into_any_element()
    }
}

fn note_line(theme: Theme, text: &'static str) -> AnyElement {
    let colors = theme::colors(theme);
    div()
        .px(px(20.0))
        .py(px(24.0))
        .flex()
        .justify_center()
        .text_size(typography::BODY.size)
        .text_color(colors.secondary_label)
        .child(text)
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::call_stats::StatDir;

    fn call(number: &str, date_ms: i64, duration_s: u64, dir: StatDir) -> StatCall {
        StatCall {
            number: number.into(),
            name: None,
            date_ms,
            duration_s,
            dir,
            whatsapp: false,
        }
    }

    #[test]
    fn export_pill_texts() {
        assert_eq!(export_pill_text(0), "No calls in this range");
        assert_eq!(export_pill_text(1), "Exported 1 person");
        assert_eq!(export_pill_text(12), "Exported 12 people");
    }

    #[test]
    fn bar_fraction_edges() {
        assert_eq!(bar_frac(0, 0), 0.0);
        assert_eq!(bar_frac(0, 100), 0.0);
        assert_eq!(bar_frac(5, 0), 0.0);
        assert_eq!(bar_frac(100, 100), 1.0);
        assert_eq!(bar_frac(50, 100), 0.5);
        // Tiny but non-zero stays visible.
        assert_eq!(bar_frac(1, 10_000), BAR_MIN);
        assert_eq!(bar_frac(u64::MAX, 1), 1.0);
    }

    #[test]
    fn bars_scale_to_the_busiest_day_and_mark_today() {
        let now = 7 * 86_400_000;
        let calls = [
            call("1", now, 100, StatDir::Outgoing),
            call("1", now - 86_400_000, 50, StatDir::Incoming),
        ];
        let bars = bar_vms(&call_stats::last_days(&calls, now, 0, 7));
        assert_eq!(bars.len(), 7);
        assert_eq!(bars[6].frac, 1.0);
        assert!(bars[6].today && !bars[5].today);
        assert_eq!(bars[5].frac, 0.5);
        assert_eq!(bars[0].frac, 0.0);
    }

    #[test]
    fn the_block_is_hidden_without_calls_and_shares_text() {
        let now = 10 * 86_400_000;
        let calls = [call("0555 111 22 33", now - 1000, 90, StatDir::Incoming)];
        assert!(block_vm(&calls, &["0999"], "Zoe", now, 0).is_none());
        let vm = block_vm(&calls, &["+905551112233"], "Zoe", now, 0).expect("calls");
        assert_eq!(vm.incoming, 1);
        assert_eq!(vm.bars.len(), 7);
        assert!(vm.share.starts_with("Calls with Zoe"));
    }

    #[test]
    fn the_page_lists_people_and_is_empty_without_calls() {
        let now = 10 * 86_400_000;
        let empty = page_vm(&[], Range::Week, now, 0);
        assert_eq!(empty.counts.calls, 0);
        assert_eq!(empty.bars.len(), 7);
        assert!(empty.people.is_empty());
        let calls = [call("0555 111 22 33", now - 1000, 90, StatDir::Incoming)];
        let page = page_vm(&calls, Range::Weeks8, now, 0);
        assert_eq!(page.bars.len(), 8);
        assert_eq!(page.people.len(), 1);
        assert!(page.people[0].detail.contains("1 call"));
    }

    #[test]
    fn page_ids_stay_on_the_pages_prefix() {
        use crate::settings_ui::Sub;
        assert!(SEGMENT_PREFIX.starts_with(Sub::Stats.prefix()));
        assert!(PERSON_PREFIX.starts_with(Sub::Stats.prefix()));
        assert!(CSV_ID.starts_with(Sub::Stats.prefix()));
        assert!(!CSV_ID.starts_with(SEGMENT_PREFIX) && !CSV_ID.starts_with(PERSON_PREFIX));
        assert!(SHARE_ID.starts_with(Sub::Stats.prefix()));
        assert!(!SHARE_ID.starts_with(SEGMENT_PREFIX) && !SHARE_ID.starts_with(PERSON_PREFIX));
        assert_ne!(SHARE_ID, CSV_ID);
        assert!(!NUMBER_SHARE.starts_with("set-"));
        assert!(crate::details_ui::is_contact_id(CONTACT_SHARE));
        assert!(!crate::details_ui::is_contact_id(NUMBER_SHARE));
    }

    #[test]
    fn bar_ids_parse_and_stay_apart_from_the_sheet_ids() {
        assert_eq!(bar_index("st-bar-3"), Some(3));
        assert_eq!(bar_index("st-bar-"), None);
        assert_eq!(bar_index("st-bar-x"), None);
        assert_eq!(bar_index("sheet-sb-3"), None);
        assert_eq!(bucket_row_index("sheet-sb-0"), Some(0));
        assert_eq!(bucket_row_index("sheet-sb-12"), Some(12));
        assert_eq!(bucket_row_index(BUCKET_CLOSE), None);
        assert_eq!(bucket_row_index("st-bar-3"), None);
        assert_eq!(bucket_row_index("sheet-sd-yes"), None);
        assert!(BUCKET_CLOSE.starts_with(BUCKET_PREFIX));
        for other in ["sheet-sd-yes", "sheet-sd-no", "sheet-sim-0", "sheet-stats"] {
            assert!(!other.starts_with(BUCKET_PREFIX));
        }
        for id in [BUCKET_CLOSE, "sheet-sb-0"] {
            assert!(id.starts_with("sheet-") && !id.starts_with("sheet-c"));
            assert!(!id.starts_with("sheet-n") && !id.starts_with("sheet-del-"));
        }
        assert!(!BAR_PREFIX.starts_with("set-") && !BAR_PREFIX.starts_with("sheet-"));
    }

    #[test]
    fn only_page_bars_with_calls_are_tappable() {
        let bars = bar_vms(&[
            DayBar {
                day_start_ms: 0,
                end_ms: 10,
                label: "Mon".into(),
                talk_s: 0,
                calls: 0,
            },
            DayBar {
                day_start_ms: 10,
                end_ms: 20,
                label: "Tue".into(),
                talk_s: 60,
                calls: 2,
            },
        ]);
        assert_eq!(bars[1].calls, 2);
        assert!(!bar_tappable(&bars[0], true));
        assert!(bar_tappable(&bars[1], true));
        assert!(!bar_tappable(&bars[1], false));
    }
}
