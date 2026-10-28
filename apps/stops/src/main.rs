//! Rovsti Stops: set a destination, fall asleep on the bus, get woken up
//! before your stop. Works offline: GPS fixes are compared on-device by
//! `rovsti_geo`, no map server or account involved.

use std::time::Duration;

use gpui::{
    App, Bounds, Context, IntoElement, ParentElement, Render, SharedString,
    StatefulInteractiveElement, Styled, Window, WindowBounds, WindowOptions, div, prelude::*, px,
    size,
};
use rovsti_geo::AlarmRule;
use rovsti_ui::{
    Icon, IconAssets,
    components::{empty_state, glass_pill, large_title, section},
    current_theme,
    glass::{GlassSpec, glass},
    tab_bar::{Tab, TabBar, content_bottom_inset},
    tokens::{Theme, typography},
};

const TABS: [&str; 3] = ["Trip", "Saved", "Settings"];
const TAB_ICONS: [Icon; 3] = [
    Icon::LucideNavigation,
    Icon::LucideBookmark,
    Icon::LucideSettings,
];

/// The choices offered in the "Wake me" picker.
const RULES: [(&str, AlarmRule); 4] = [
    ("300 m", AlarmRule::Radius { metres: 300.0 }),
    ("500 m", AlarmRule::Radius { metres: 500.0 }),
    ("1 km", AlarmRule::Radius { metres: 1_000.0 }),
    (
        "2 min",
        AlarmRule::Eta {
            before: Duration::from_secs(120),
            min_metres: 300.0,
        },
    ),
];

struct StopsApp {
    tabs: TabBar,
    motion: rovsti_ui::motion::Motion,
    page: rovsti_ui::motion::Entrance,
    rule: usize,
}

impl StopsApp {
    fn new() -> Self {
        Self {
            tabs: TabBar::new(
                TABS.into_iter()
                    .zip(TAB_ICONS)
                    .map(|(label, icon)| Tab::new(icon, label)),
            ),
            motion: Default::default(),
            page: Default::default(),
            rule: 1,
        }
    }

    fn rule_picker(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let colors = theme.colors();
        div()
            .flex()
            .gap(px(8.0))
            .px(px(16.0))
            .py(px(12.0))
            .children(RULES.iter().enumerate().map(|(i, (label, _))| {
                let selected = i == self.rule;
                let chip = if selected {
                    glass(GlassSpec::lens(theme))
                } else {
                    div()
                };
                chip.id(("rule", i))
                    .flex_1()
                    .h(px(40.0))
                    .rounded_full()
                    .flex()
                    .items_center()
                    .justify_center()
                    .text_size(typography::SUBHEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(if selected {
                        colors.accent
                    } else {
                        colors.secondary_label
                    })
                    .child(*label)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.rule = i;
                        cx.notify();
                    }))
            }))
    }

    fn trip(&self, theme: Theme, cx: &mut Context<Self>) -> impl IntoElement {
        let colors = theme.colors();
        let rule_text: SharedString = match RULES[self.rule].1 {
            AlarmRule::Radius { metres } => {
                format!("You'll be woken {metres:.0} m before the stop.").into()
            }
            AlarmRule::Eta { before, .. } => format!(
                "You'll be woken about {} min before arriving.",
                before.as_secs() / 60
            )
            .into(),
        };
        div()
            .flex()
            .flex_col()
            .gap(px(24.0))
            .child(section(
                theme,
                Some("Wake me".into()),
                [
                    self.rule_picker(theme, cx).into_any_element(),
                    div()
                        .px(px(16.0))
                        .pb(px(12.0))
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.secondary_label)
                        .child(rule_text)
                        .into_any_element(),
                ],
            ))
            .child(empty_state(
                theme,
                "Where are you going?",
                "Pick a destination from Saved, or share a location to Rovsti Stops from any maps app.",
            ))
            .child(div().flex().justify_center().child(glass_pill(theme, "Choose destination").id("choose")))
    }
}

impl Render for StopsApp {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let mut motion = std::mem::take(&mut self.motion);
        motion.frame(window, |dt| self.tabs.tick(dt) | self.page.tick(dt));
        self.motion = motion;
        let tab = self.tabs.selected();
        let entity = cx.entity();
        let touch_entity = entity.clone();

        let body = match tab {
            0 => self.trip(theme, cx).into_any_element(),
            1 => empty_state(
                theme,
                "No Saved Stops",
                "Stops you travel to often appear here for one-tap trips.",
            )
            .into_any_element(),
            _ => empty_state(
                theme,
                "Settings",
                "Alarm sound, vibration and GPS battery use.",
            )
            .into_any_element(),
        };

        div()
            .size_full()
            .relative()
            .on_mouse_up(
                gpui::MouseButton::Left,
                cx.listener(|this, _, _, cx| {
                    this.tabs.release();
                    cx.notify();
                }),
            )
            .child(rovsti_ui::components::backdrop(
                theme,
                theme.colors().orange,
            ))
            .child(
                div()
                    .id("page")
                    .size_full()
                    .overflow_y_scroll()
                    .pb(content_bottom_inset())
                    .opacity(self.page.progress())
                    .child(
                        div()
                            .relative()
                            .top(px(self.page.offset(24.0)))
                            .child(large_title(theme, TABS[tab], None))
                            .child(body),
                    ),
            )
            .child(self.tabs.render(
                theme,
                move |index, _, cx| {
                    entity.update(cx, |this, cx| {
                        this.tabs.press();
                        if this.tabs.select(index) {
                            this.page.restart();
                        }
                        cx.notify();
                    });
                },
                move |phase, _, cx| {
                    touch_entity.update(cx, |this, cx| {
                        this.tabs.touch(phase);
                        cx.notify();
                    });
                },
            ))
    }
}

#[gpui_platform::main]
fn main() {
    gpui_platform::application()
        .with_assets(IconAssets::new())
        .run(|cx: &mut App| {
            cx.open_window(
                WindowOptions {
                    window_bounds: Some(WindowBounds::Windowed(Bounds::centered(
                        None,
                        size(px(402.0), px(874.0)),
                        cx,
                    ))),
                    ..Default::default()
                },
                |_, cx| cx.new(|_| StopsApp::new()),
            )
            .expect("failed to open the Rovsti Stops window");
            cx.activate(true);
        });
}
