//! Drawing and touching the In-Call zone (stories 15.6, 15.7): the one-call,
//! two-call and conference faces, the Held card, the conference panel and the
//! shared elements that travel between them (S19 to S22).
//!
//! Everything is absolutely positioned inside one fixed-height content box, so
//! a scene change moves only what the moment moves; the grid and End below the
//! zone are never touched. Avatars are the shared elements: each frame they sit
//! at a pose interpolated from the springs in [`ZoneState`].

use gpui::{
    AnyElement, Context, FontWeight, Hsla, InteractiveElement, IntoElement, MouseButton,
    ParentElement, Pixels, Point, Role, SharedString, StatefulInteractiveElement, Styled,
    TouchPhase, Window, div, px, svg,
};
use rovsti_core::call::CallState;
use rovsti_ui::{
    Icon,
    components::flat_glass,
    motion::press_scale,
    motion_tokens::scale,
    theme::{self, call_colors},
    tokens::{Theme, mix, typography},
    touch::{Pointer, TAP_SLOP, pointer_surface, touch_surface},
};

use crate::{
    PhoneApp,
    incall::CallView,
    zone::{Face, Party, Row, lerp, slots},
};

/// Where an avatar sits: centre x, top y (zone coordinates), diameter.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Pose {
    pub cx: f32,
    pub y: f32,
    pub size: f32,
}

impl Pose {
    pub(crate) fn lerp(self, other: Self, t: f32) -> Self {
        Self {
            cx: lerp(self.cx, other.cx, t),
            y: lerp(self.y, other.y, t),
            size: lerp(self.size, other.size, t),
        }
    }

    fn shifted(self, dx: f32) -> Self {
        Self {
            cx: self.cx + dx,
            ..self
        }
    }
}

pub(crate) fn single_pose(w: f32) -> Pose {
    Pose {
        cx: w * 0.5,
        y: 0.0,
        size: slots::ONE_AVATAR,
    }
}

pub(crate) fn active_pose(w: f32) -> Pose {
    Pose {
        cx: w * 0.5,
        y: 0.0,
        size: slots::TWO_AVATAR,
    }
}

/// The avatar in a Held card whose top edge is `card_y`.
pub(crate) fn held_pose(card_y: f32) -> Pose {
    Pose {
        cx: slots::SIDE + 12.0 + slots::HELD_AVATAR * 0.5,
        y: card_y + (slots::HELD_H - slots::HELD_AVATAR) * 0.5,
        size: slots::HELD_AVATAR,
    }
}

/// Stack position `i` of `n` (at most 3) in the conference header: overlapped by half.
pub(crate) fn stack_pose(w: f32, i: usize, n: usize) -> Pose {
    let size = slots::STACK;
    let step = size * 0.5;
    let total = step * (n.max(1) - 1) as f32;
    Pose {
        cx: w * 0.5 - total * 0.5 + step * i as f32,
        y: 0.0,
        size,
    }
}

fn at(pose: Pose) -> gpui::Div {
    div()
        .absolute()
        .left(px(pose.cx - pose.size * 0.5))
        .top(px(pose.y))
        .size(px(pose.size))
}

impl PhoneApp {
    /// A plain avatar for a party that is not the live call (the Held card's,
    /// a row's), with an optional ring.
    fn small_avatar(
        &self,
        theme: Theme,
        party: &Party,
        pose: Pose,
        ring: Option<Hsla>,
        opacity: f32,
    ) -> AnyElement {
        let mut slot = at(pose).opacity(opacity);
        if let Some(ring) = ring {
            slot = slot.child(
                div()
                    .absolute()
                    .top(px(-3.0))
                    .left(px(-3.0))
                    .size(px(pose.size + 6.0))
                    .rounded_full()
                    .border(px(2.0))
                    .border_color(ring),
            );
        }
        slot.child(crate::contacts::person_avatar(
            theme,
            party.hue,
            party.monogram.as_ref(),
            pose.size,
            None,
        ))
        .into_any_element()
    }

    /// The whole zone: a fixed-height box that scrolls only on small or
    /// large-font screens. `view` is the foreground call.
    pub(crate) fn render_zone(
        &self,
        theme: Theme,
        view: &CallView,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let ic = &self.incall;
        let z = &ic.zone;
        let w = f32::from(window.viewport_size().width);
        let colors = theme::colors(theme);
        let reduced = self.reduced;
        let layout = z.layout.value.clamp(0.0, 1.0);
        let weave = z.weave.value.clamp(0.0, 1.0);
        let bloom = ic.bloom.value.clamp(0.0, 1.0);
        let cross = if reduced { z.cross.value() } else { 1.0 };
        let conference = z.face == Face::Conference;
        let face = if z.ended.is_some() { Face::Two } else { z.face };
        let waiting_dim = 1.0 - 0.15 * ic.waiting_in.value.clamp(0.0, 1.0);
        let mut content = div()
            .relative()
            .w_full()
            .h(px(slots::CONTENT_H))
            .opacity(cross * waiting_dim);

        // The pose of the live call's avatar, the Held card's avatar, and the name blocks.
        let held_y = if conference {
            slots::CONF_HELD_Y
        } else {
            slots::TWO_HELD_Y
        };
        let crossing = z.crossing.as_ref().filter(|_| z.swap.value < 0.999);
        let st = z.swap.value.clamp(0.0, 1.0);
        let dx = 12.0 * (std::f32::consts::PI * st).sin();

        if !conference || weave < 1.0 {
            let fade = if conference { 1.0 - weave } else { 1.0 };
            // ---- The live call's avatar --------------------------------------
            let drop = z.drop.value;
            let held_slot_has_fg = face == Face::Single && layout > 0.003;
            let size = lerp(slots::ONE_AVATAR, slots::TWO_AVATAR, layout);
            let base = Pose {
                cx: w * 0.5,
                y: 0.0,
                size,
            };
            let (pose, extra_scale, a_opacity) = match (crossing, &z.ended) {
                (Some(_), _) => (
                    held_pose(held_y).lerp(active_pose(w), st).shifted(-dx),
                    1.0,
                    1.0,
                ),
                (None, _) => (
                    base,
                    if drop < 0.999 { 0.6 + 0.4 * drop } else { 1.0 },
                    if drop < 0.999 {
                        (drop * 1.6).clamp(0.0, 1.0)
                    } else {
                        1.0
                    },
                ),
            };
            let primary: AnyElement = if let Some(ended) = &z.ended {
                self.small_avatar(theme, &ended.party, base, None, 0.6)
            } else if held_slot_has_fg {
                div().into_any_element()
            } else {
                let flown = z
                    .split_fly
                    .as_ref()
                    .map_or(1.0, |_| z.split_t.value.clamp(0.0, 1.0));
                let scaled = Pose {
                    size: pose.size * extra_scale,
                    y: pose.y + pose.size * (1.0 - extra_scale) * 0.5,
                    ..pose
                };
                at(scaled)
                    .opacity(a_opacity * fade * flown)
                    .child(self.avatar_view(
                        theme,
                        view,
                        scaled.size,
                        self.caller_photo(&view.photo, cx),
                    ))
                    .into_any_element()
            };
            content = content.child(primary);

            // ---- The Held card's avatar (S19 / S20 / ended-to-single) ----------
            let held_party = if held_slot_has_fg {
                Some(Party::of_view(view))
            } else {
                z.held.clone().or_else(|| z.held_ghost.clone())
            };
            if let Some(party) = held_party {
                let to = held_pose(held_y);
                let pose = match crossing {
                    Some(_) => active_pose(w).lerp(to, st).shifted(dx),
                    None => single_pose(w).lerp(to, layout),
                };
                let visible = z.held_t.value.clamp(0.0, 1.0).max(layout);
                let ring = Some(Hsla {
                    a: 0.9 * visible,
                    ..call_colors(theme).warning
                });
                content =
                    content.child(self.small_avatar(theme, &party, pose, ring, visible * fade));
            }

            // ---- Names, status, number line, chip row ---------------------------
            let name_y = lerp(slots::ONE_NAME_Y, slots::TWO_NAME_Y, layout);
            let status_y = lerp(slots::ONE_STATUS_Y, slots::TWO_STATUS_Y, layout);
            let chips_y = lerp(slots::ONE_CHIPS_Y, slots::TWO_CHIPS_Y, layout);
            let name_block = |text: SharedString, opacity: f32| {
                div()
                    .absolute()
                    .top(px(name_y))
                    .left_0()
                    .w_full()
                    .flex()
                    .justify_center()
                    .opacity(opacity)
                    .child(
                        div()
                            .w_full()
                            .px(px(16.0))
                            .text_center()
                            .text_size(px(28.0))
                            .font_weight(FontWeight(700.0))
                            .text_color(colors.label)
                            .truncate()
                            .child(text),
                    )
            };
            let (name, tail): (SharedString, Option<SharedString>) = match &z.ended {
                Some(ended) => (ended.party.name.clone(), None),
                None => (view.name.clone(), None),
            };
            let _ = tail;
            if let Some(crossing) = crossing {
                content = content
                    .child(name_block(crossing.out.name.clone(), (1.0 - st) * fade))
                    .child(name_block(name, st * fade));
            } else {
                content = content.child(name_block(name, bloom * fade));
            }
            if z.ended.is_some() {
                content = content.child(
                    div()
                        .absolute()
                        .top(px(status_y))
                        .w_full()
                        .flex()
                        .justify_center()
                        .text_size(typography::HEADLINE.size)
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(colors.secondary_label)
                        .child("Call ended"),
                );
            } else {
                content = content.child(
                    div()
                        .absolute()
                        .top(px(status_y))
                        .left_0()
                        .w_full()
                        .opacity(bloom * fade * if crossing.is_some() { st } else { 1.0 })
                        .child(ic.status.clone()),
                );
            }
            let secondary = match (view.route, &view.secondary) {
                (rovsti_core::wire::AudioRoute::Bluetooth, _) => {
                    Some(SharedString::from("Bluetooth"))
                }
                (rovsti_core::wire::AudioRoute::WiredHeadset, _) => {
                    Some(SharedString::from("Headphones"))
                }
                (_, s) => s.clone(),
            };
            content = content
                .child(
                    div()
                        .absolute()
                        .top(px(slots::ONE_NUMBER_Y))
                        .left_0()
                        .w_full()
                        .h(px(18.0))
                        .flex()
                        .justify_center()
                        .opacity((1.0 - layout) * fade)
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.secondary_label)
                        .children(secondary),
                )
                // The caller block: held, it copies the number (no tap action).
                .child(
                    div()
                        .id(crate::incall_note::WHO)
                        .absolute()
                        .top(px(name_y))
                        .left_0()
                        .w_full()
                        .h(px(slots::ONE_NUMBER_Y + 18.0 - slots::ONE_NAME_Y))
                        .aria_label("Caller, hold to copy the number")
                        .child(touch_surface(Self::touch_listener(
                            cx,
                            Some(crate::incall_note::WHO.into()),
                        ))),
                )
                // The reserved chip row: SIM (16.5) and quality (21.4) chips land here.
                .child(
                    div()
                        .id("ic-chips")
                        .absolute()
                        .top(px(chips_y))
                        .left_0()
                        .w_full()
                        .h(px(slots::CHIP_ROW_H))
                        .flex()
                        .justify_center()
                        .items_center()
                        .gap(px(6.0))
                        .children(self.incall_sim_chip(theme, cx))
                        .children(self.incall_note_capsule(theme, view, cx)),
                );

            // ---- The Held card ----------------------------------------------------
            let card_party = if held_slot_has_fg {
                Some(Party::of_view(view))
            } else {
                z.held.clone().or_else(|| z.held_ghost.clone())
            };
            if let Some(party) = card_party {
                content = content.child(self.held_card(
                    theme,
                    &party,
                    crossing.map(|c| (c.inn.name.clone(), st)),
                    held_y,
                    z.held_t.value.clamp(0.0, 1.0) * fade,
                    cx,
                ));
            }
        }

        // ---- The conference ----------------------------------------------------
        if conference || weave > 0.003 {
            content = content.child(self.conference_zone(theme, w, weave, cx));
            if let (true, Some(party)) = (conference, z.held.as_ref()) {
                content = content.child(self.held_card(
                    theme,
                    party,
                    None,
                    slots::CONF_HELD_Y,
                    z.held_t.value.clamp(0.0, 1.0),
                    cx,
                ));
            }
        }

        // ---- A participant flying up (S22 Split) --------------------------------
        if let Some((index, party)) = &z.split_fly {
            let t = z.split_t.value;
            let from_y = slots::CONF_PANEL_Y + 8.0 + *index as f32 * slots::PANEL_ROW;
            let from = Pose {
                cx: slots::SIDE + 12.0 + 18.0,
                y: from_y,
                size: 36.0 * 1.15,
            };
            content = content.child(self.small_avatar(
                theme,
                party,
                from.lerp(active_pose(w), t.clamp(0.0, 1.0)),
                None,
                (1.0 - ((t - 0.85) / 0.15).clamp(0.0, 1.0)).max(0.0),
            ));
        }

        div()
            .id("ic-zone")
            .relative()
            .size_full()
            .overflow_y_scroll()
            .child(content)
            .into_any_element()
    }

    /// The Held card: a 64 px flat-glass capsule tinted with the warning colour.
    /// The whole card is the swap target (finger-up).
    fn held_card(
        &self,
        theme: Theme,
        party: &Party,
        outgoing: Option<(SharedString, f32)>,
        y: f32,
        opacity: f32,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let press = self.presses.value("ix-held").clamp(0.0, 1.0);
        let k = press_scale(press, 0.98);
        let inset = slots::SIDE;
        let id = "ix-held";
        let name_x = slots::SIDE + 12.0 + slots::HELD_AVATAR + 12.0;
        let texts = |name: SharedString, sub: gpui::AnyElement, alpha: f32| {
            div()
                .absolute()
                .left(px(name_x - inset))
                .right(px(64.0))
                .top_0()
                .h_full()
                .flex()
                .flex_col()
                .justify_center()
                .opacity(alpha)
                .child(
                    div()
                        .truncate()
                        .text_size(typography::HEADLINE.size)
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(colors.label)
                        .child(name),
                )
                .child(sub)
        };
        let live_sub = div()
            .child(self.incall.zone.held_line.clone())
            .into_any_element();
        let mut card = flat_glass(theme, Some((call_colors(theme).warning, 0.12)))
            .relative()
            .size_full()
            .rounded_full()
            .overflow_hidden();
        match outgoing {
            Some((old, st)) => {
                card = card
                    .child(texts(
                        old,
                        static_sub("On hold", colors.secondary_label),
                        1.0 - st,
                    ))
                    .child(texts(party.name.clone(), live_sub, st));
            }
            None => card = card.child(texts(party.name.clone(), live_sub, 1.0)),
        }
        let swap_button = div()
            .absolute()
            .right(px(10.0))
            .top(px(10.0))
            .size(px(44.0))
            .flex()
            .items_center()
            .justify_center()
            .child(
                div()
                    .size(px(36.0))
                    .rounded_full()
                    .bg(Hsla {
                        a: 0.14 + 0.1 * press,
                        ..colors.label
                    })
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        svg()
                            .path(Icon::LucideArrowUpDown.path())
                            .size(px(20.0))
                            .text_color(colors.label),
                    ),
            );
        card = card.child(swap_button);
        div()
            .id(id)
            .role(Role::Button)
            .aria_label(party.held_aria())
            .absolute()
            .left(px(inset + (1.0 - k) * 150.0))
            .right(px(inset + (1.0 - k) * 150.0))
            .top(px(y + slots::HELD_H * (1.0 - k) * 0.5))
            .h(px(slots::HELD_H * k))
            .opacity(opacity)
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, window, cx| this.tap_press(id, window, cx)),
            )
            .child(card)
            .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
            .into_any_element()
    }

    /// The conference header (avatar stack, title, timer) and the participant panel.
    fn conference_zone(
        &self,
        theme: Theme,
        w: f32,
        weave: f32,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let z = &self.incall.zone;
        let colors = theme::colors(theme);
        let Some(conf) = &z.conference else {
            return div().into_any_element();
        };
        let holding = self
            .incall
            .view
            .as_ref()
            .is_some_and(|v| v.state == CallState::Holding);
        let ring = holding.then(|| call_colors(theme).warning);
        let mut root = div().absolute().inset_0().opacity(weave);
        // ---- Avatar stack ----
        let n = conf.stack.len();
        let from_a = active_pose(w);
        let from_b = held_pose(slots::TWO_HELD_Y);
        for (i, (mono, hue)) in conf.stack.iter().enumerate() {
            let to = stack_pose(w, i, n + usize::from(conf.extra > 0));
            let from = if i == 0 { from_a } else { from_b };
            let pose = from.lerp(to, weave);
            let party = Party {
                id: -1 - i as i32,
                name: SharedString::default(),
                secondary: None,
                monogram: mono.clone(),
                hue: *hue,
                connect_time_ms: 0,
                hold_since_ms: 0,
            };
            root = root.child(self.small_avatar(theme, &party, pose, ring, 1.0));
        }
        if n == 0 {
            // The carrier hides the participants: one generic avatar for the group.
            let pose = stack_pose(w, 0, 1);
            root = root.child(
                at(pose)
                    .rounded_full()
                    .bg(colors.fill)
                    .border(px(1.5))
                    .border_color(colors.hairline)
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        svg()
                            .path(Icon::LucideUsers.path())
                            .size(px(26.0))
                            .text_color(colors.secondary_label),
                    ),
            );
        }
        if conf.extra > 0 {
            let pose = stack_pose(w, n, n + 1);
            let mut label = String::with_capacity(4);
            label.push('+');
            label.push_str(&conf.extra.to_string());
            root = root.child(
                at(pose)
                    .rounded_full()
                    .bg(colors.fill)
                    .border(px(1.5))
                    .border_color(colors.hairline)
                    .flex()
                    .items_center()
                    .justify_center()
                    .text_size(typography::HEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.label)
                    .child(SharedString::from(label)),
            );
        }
        root = root
            .child(
                div()
                    .absolute()
                    .top(px(slots::CONF_TITLE_Y))
                    .w_full()
                    .flex()
                    .justify_center()
                    .text_size(px(20.0))
                    .font_weight(FontWeight(600.0))
                    .text_color(colors.label)
                    .child("Conference"),
            )
            .child(
                div()
                    .absolute()
                    .top(px(slots::CONF_TIMER_Y))
                    .w_full()
                    .flex()
                    .justify_center()
                    .child(z.conf_line.clone()),
            );
        // ---- Panel ----
        let rise = (1.0 - weave) * 48.0;
        let age = z.weave_age;
        let scroll = z.scroll.offset().y;
        let first = ((-f32::from(scroll)) / slots::PANEL_ROW).floor().max(0.0) as usize;
        let rows: Vec<AnyElement> = conf
            .rows
            .iter()
            .enumerate()
            .skip(first)
            .take(5)
            .map(|(i, row)| self.conference_row(theme, i, row, age))
            .collect();
        let summary_row = conf.summary.as_ref().map(|text| {
            div()
                .h(px(slots::PANEL_ROW))
                .px(px(12.0))
                .flex()
                .items_center()
                .gap(px(12.0))
                .child(
                    div()
                        .size(px(36.0))
                        .rounded_full()
                        .bg(colors.fill)
                        .flex()
                        .items_center()
                        .justify_center()
                        .child(
                            svg()
                                .path(Icon::LucideUsers.path())
                                .size(px(18.0))
                                .text_color(colors.secondary_label),
                        ),
                )
                .child(
                    div()
                        .flex_1()
                        .flex()
                        .flex_col()
                        .child(
                            div()
                                .text_size(typography::HEADLINE.size)
                                .font_weight(typography::HEADLINE.weight)
                                .text_color(colors.label)
                                .child("Conference"),
                        )
                        .child(
                            div()
                                .text_size(typography::FOOTNOTE.size)
                                .text_color(colors.secondary_label)
                                .child(text.clone()),
                        ),
                )
        });
        let total_h = conf.rows.len() as f32 * slots::PANEL_ROW;
        let list = div()
            .id("cf-list")
            .size_full()
            .overflow_y_scroll()
            .track_scroll(&z.scroll)
            .child(
                div()
                    .h(px(total_h.max(if conf.summary.is_some() {
                        slots::PANEL_ROW
                    } else {
                        0.0
                    })))
                    .child(div().h(px(first as f32 * slots::PANEL_ROW)))
                    .children(rows)
                    .children(summary_row),
            );
        let entity = cx.entity();
        let panel = flat_glass(theme, None)
            .id("cf-panel")
            .role(Role::List)
            .aria_label("Conference participants")
            .absolute()
            .left(px(slots::SIDE))
            .right(px(slots::SIDE))
            .top(px(slots::CONF_PANEL_Y + rise))
            .h(px(slots::PANEL_H))
            .rounded(px(slots::PANEL_RADIUS))
            .overflow_hidden()
            .opacity(weave)
            .child(list)
            .child(pointer_surface(
                std::rc::Rc::clone(&z.bounds),
                move |pointer, position, window, cx| {
                    entity.update(cx, |this, cx| {
                        this.conf_pointer(pointer, position, window, cx)
                    });
                },
            ));
        root.child(panel).into_any_element()
    }

    /// One participant row: avatar 36, name, subtitle, and the actions the child reports.
    fn conference_row(&self, theme: Theme, i: usize, row: &Row, age: f32) -> AnyElement {
        let z = &self.incall.zone;
        let colors = theme::colors(theme);
        let party = Party {
            id: row.id,
            name: row.label.clone(),
            secondary: None,
            monogram: row.monogram.clone(),
            hue: row.hue,
            connect_time_ms: 0,
            hold_since_ms: 0,
        };
        // Rows fade up staggered 30 ms (the first six) after the weave began.
        let stagger = ((age - 0.03 * i.min(6) as f32) / 0.18).clamp(0.0, 1.0);
        let out = z.eject_progress(row.id);
        let key_s = crate::contacts::list_key("cf-s", i);
        let key_e = crate::contacts::list_key("cf-e", i);
        let (ps, pe) = (
            self.presses.value(key_s.as_str()).clamp(0.0, 1.0),
            self.presses.value(key_e.as_str()).clamp(0.0, 1.0),
        );
        let button = |icon: Icon, color: Hsla, press: f32| {
            div()
                .size(px(44.0))
                .flex_none()
                .flex()
                .items_center()
                .justify_center()
                .child(
                    div()
                        .size(px(36.0 * press_scale(press, scale::PRESS_CARD)))
                        .rounded_full()
                        .bg(Hsla {
                            a: 0.10 + 0.14 * press,
                            ..color
                        })
                        .flex()
                        .items_center()
                        .justify_center()
                        .child(svg().path(icon.path()).size(px(18.0)).text_color(color)),
                )
        };
        let danger = call_colors(theme).danger;
        div()
            .id(("cf-row", i))
            .role(Role::ListItem)
            .aria_label(row.aria.clone())
            .h(px(slots::PANEL_ROW))
            .px(px(12.0))
            .flex()
            .items_center()
            .gap(px(12.0))
            .opacity((1.0 - out) * stagger)
            .relative()
            .left(px(24.0 * out))
            .top(px((1.0 - stagger) * 8.0))
            .child(self.small_avatar_inline(theme, &party, 36.0))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .truncate()
                            .text_size(typography::HEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(colors.label)
                            .child(row.label.clone()),
                    )
                    .child(
                        div()
                            .truncate()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child(row.subtitle.clone()),
                    ),
            )
            .children(
                row.can_split
                    .then(|| button(Icon::LucideSplit, colors.label, ps)),
            )
            .children(
                row.can_drop
                    .then(|| button(Icon::LucideUserMinus, danger, pe)),
            )
            .into_any_element()
    }

    fn small_avatar_inline(&self, theme: Theme, party: &Party, size: f32) -> impl IntoElement {
        crate::contacts::person_avatar(theme, party.hue, party.monogram.as_ref(), size, None)
    }

    // ---- Touch --------------------------------------------------------------------

    /// Which row and which button a window position falls on: `(row, 0 = Split, 1 = End)`.
    fn conf_hit(&self, position: Point<Pixels>) -> Option<(usize, u8)> {
        let z = &self.incall.zone;
        let conf = z.conference.as_ref()?;
        let b = z.bounds.get();
        let x = f32::from(position.x - b.origin.x);
        let y = f32::from(position.y - b.origin.y) - f32::from(z.scroll.offset().y);
        if x < 0.0 || y < 0.0 || x > f32::from(b.size.width) {
            return None;
        }
        let row = (y / slots::PANEL_ROW).floor() as usize;
        let r = conf.rows.get(row)?;
        let from_right = f32::from(b.size.width) - x;
        // The End button is the rightmost 44 + 12; Split sits to its left.
        let (end_w, split_w) = (
            if r.can_drop { 44.0 } else { 0.0 },
            if r.can_split { 44.0 } else { 0.0 },
        );
        let edge = 12.0;
        if r.can_drop && from_right >= edge && from_right < edge + end_w {
            Some((row, 1))
        } else if r.can_split && from_right >= edge + end_w && from_right < edge + end_w + split_w {
            Some((row, 0))
        } else {
            None
        }
    }

    pub(crate) fn conf_pointer(
        &mut self,
        pointer: Pointer,
        position: Point<Pixels>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.incoming.covers() || self.sheet.is_visible() || self.route.is_open() {
            return;
        }
        let scroll = f32::from(self.incall.zone.scroll.offset().y).abs();
        match pointer {
            Pointer::Touch(TouchPhase::Started) => {
                self.incall
                    .zone
                    .tap
                    .begin(f32::from(position.x), f32::from(position.y), scroll);
                if let Some((row, kind)) = self.conf_hit(position) {
                    self.incall.zone.press_row = Some((row, kind));
                    let key =
                        crate::contacts::list_key(if kind == 0 { "cf-s" } else { "cf-e" }, row);
                    self.presses.touch_down(key.as_str().to_owned());
                    cx.notify();
                }
            }
            Pointer::Touch(TouchPhase::Moved) => {
                let moved = self
                    .incall
                    .zone
                    .tap
                    .moved(f32::from(position.x), f32::from(position.y));
                if moved && self.incall.zone.press_row.take().is_some() {
                    self.presses.release_all();
                    cx.notify();
                }
            }
            Pointer::Touch(_) => {
                self.incall
                    .zone
                    .tap
                    .finish(f32::from(position.x), f32::from(position.y));
                self.incall.zone.press_row = None;
                self.presses.release_all();
                cx.notify();
            }
            Pointer::Click => {
                if !self.incall.zone.tap.allow_click(scroll) {
                    return;
                }
                let Some((row, kind)) = self.conf_hit(position) else {
                    return;
                };
                self.conference_act(row, kind, window, cx);
            }
        }
    }

    /// Split or End on a participant row (finger-up, no confirmation).
    pub(crate) fn conference_act(
        &mut self,
        row: usize,
        kind: u8,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some(r) = self
            .incall
            .zone
            .conference
            .as_ref()
            .and_then(|c| c.rows.get(row).cloned())
        else {
            return;
        };
        let reduced = self.reduced;
        if kind == 0 && r.can_split {
            rovsti_ui::haptics::perform(window, gpui::HapticFeedback::Confirm);
            let party = Party {
                id: r.id,
                name: r.label.clone(),
                secondary: None,
                monogram: r.monogram.clone(),
                hue: r.hue,
                connect_time_ms: 0,
                hold_since_ms: 0,
            };
            if !reduced {
                self.incall.zone.split_fly = Some((row, party));
                self.incall.zone.split_t.snap(0.0);
                self.incall.zone.split_t.set_target(1.0);
            }
            self.backend.update(cx, |b, cx| {
                b.split(r.id, cx);
            });
        } else if kind == 1 && r.can_drop {
            rovsti_ui::haptics::perform(window, gpui::HapticFeedback::Selection);
            self.incall.zone.eject = Some(crate::zone::Eject {
                id: r.id,
                since: std::time::Instant::now(),
            });
            self.backend.update(cx, |b, cx| {
                b.disconnect(r.id, cx);
            });
        }
        cx.notify();
    }

    /// A tap on a finger-up control (`ix-…`): the press look is already showing.
    pub(crate) fn tap_press(
        &mut self,
        id: &'static str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !self.presses.touch_seen() {
            self.presses.down(id);
        }
        self.incall_tap(id, window, cx);
    }
}

/// A one-line static caption under a name (the leaving half of a cross-fade).
fn static_sub(text: &'static str, color: Hsla) -> AnyElement {
    div()
        .text_size(typography::FOOTNOTE.size)
        .text_color(color)
        .child(text)
        .into_any_element()
}

#[allow(dead_code)]
fn _tap_slop_is_shared() -> f32 {
    TAP_SLOP + mix(gpui::white(), gpui::black(), 0.0).a
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn poses_interpolate_and_end_where_they_should() {
        let w = 402.0;
        assert_eq!(single_pose(w).lerp(active_pose(w), 0.0), single_pose(w));
        assert_eq!(single_pose(w).lerp(active_pose(w), 1.0), active_pose(w));
        let p = held_pose(slots::TWO_HELD_Y);
        assert_eq!(p.size, 40.0);
        assert_eq!(p.cx, 48.0);
        assert_eq!(p.y, slots::TWO_HELD_Y + 12.0);
    }

    #[test]
    fn the_stack_is_centred_and_overlapped_by_half() {
        let w = 400.0;
        let a = stack_pose(w, 0, 3);
        let c = stack_pose(w, 2, 3);
        assert_eq!(a.size, 56.0);
        assert!(((a.cx + c.cx) * 0.5 - 200.0).abs() < 0.01);
        assert_eq!(stack_pose(w, 1, 3).cx - a.cx, 28.0);
        assert_eq!(stack_pose(w, 0, 1).cx, 200.0);
    }
}
