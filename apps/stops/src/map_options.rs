//! The Map button's sheet: the appearance choice and the offline area
//! download, plus the app-wide appearance helpers Settings shares.

use gpui::prelude::FluentBuilder as _;
use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, px,
};
use rovsti_geo::{
    Appearance,
    coord_format::{map_view_share_text, osm_view_url},
    tiles::{
        AREA_MIN_ZOOM, AREA_TILE_BUDGET, AreaPlan, MAX_TILE_ZOOM, display_zoom, estimate_bytes,
        tiles_for_area,
    },
};
use rovsti_ui::{
    components::{glass_pill, list_row, section},
    theme,
    tokens::{Theme, typography},
};

use crate::{StopsApp, picker::SheetMode};

/// The theme to force for `appearance`, or `None` to follow the system.
pub fn theme_override(appearance: Appearance) -> Option<Theme> {
    match appearance {
        Appearance::Auto => None,
        Appearance::Light => Some(Theme::Light),
        Appearance::Dark => Some(Theme::Dark),
    }
}

/// The pill after an appearance change: "Appearance: Dark".
pub fn appearance_pill(appearance: Appearance) -> String {
    format!("Appearance: {}", appearance.label())
}

/// The pill after flipping the saved-stop rings.
pub fn saved_toggle_pill(on: bool) -> &'static str {
    if on {
        "Saved stops shown"
    } else {
        "Saved stops hidden"
    }
}

/// Megabytes for an estimate, rounded, at least 1.
fn megabytes(bytes: u64) -> u64 {
    ((bytes + 500_000) / 1_000_000).max(1)
}

/// "900 tiles, about 27 MB, up to zoom 14", plus "Zoom in for street detail"
/// when the budget stopped the plan short of the highest zoom.
pub fn area_subtitle(plan: &AreaPlan) -> String {
    let n = plan.tiles.len();
    let tiles = if n == 1 {
        "1 tile".to_owned()
    } else {
        format!("{n} tiles")
    };
    let mut text = format!(
        "{tiles}, about {} MB, up to zoom {}",
        megabytes(estimate_bytes(n)),
        plan.top_zoom
    );
    if plan.capped {
        text.push_str(". Zoom in for street detail");
    }
    text
}

/// The confirm row title: "Download 900 tiles".
pub fn area_confirm_title(plan: &AreaPlan) -> String {
    match plan.tiles.len() {
        1 => "Download 1 tile".to_owned(),
        n => format!("Download {n} tiles"),
    }
}

impl StopsApp {
    /// Makes the window follow `prefs.appearance` now.
    pub(crate) fn apply_appearance(&mut self, cx: &mut Context<Self>) {
        rovsti_ui::set_theme_override(theme_override(self.prefs.appearance));
        cx.notify();
    }

    /// Picks `appearance`, saves it and says so on the pill.
    pub(crate) fn set_appearance(&mut self, appearance: Appearance, cx: &mut Context<Self>) {
        self.prefs.appearance = appearance;
        self.apply_appearance(cx);
        self.save_prefs(cx);
        self.show_pill(appearance_pill(appearance), None, cx);
    }

    /// Opens the Map options sheet.
    pub(crate) fn open_map_options(&mut self, cx: &mut Context<Self>) {
        self.area_armed = false;
        self.sheet_mode = SheetMode::MapOptions;
        self.sheet.open();
        cx.notify();
    }

    /// Shows or hides the saved-stop rings, remembering the choice.
    fn toggle_saved_on_map(&mut self, cx: &mut Context<Self>) {
        self.prefs.show_saved_on_map = !self.prefs.show_saved_on_map;
        self.save_prefs(cx);
        self.map.forget_scene();
        self.show_pill(saved_toggle_pill(self.prefs.show_saved_on_map), None, cx);
        cx.notify();
    }

    /// The tiles of the area on screen.
    fn visible_area_plan(&self) -> AreaPlan {
        let (w, h) = self.map.size();
        let (hw, hh) = (w / 2.0, h / 2.0);
        let corners =
            [(-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh)].map(|at| self.map.viewport.unproject(at));
        tiles_for_area(&corners, AREA_MIN_ZOOM, MAX_TILE_ZOOM, AREA_TILE_BUDGET)
    }

    /// Two taps: the first arms, the second starts the download.
    fn tap_area_download(&mut self, cx: &mut Context<Self>) {
        if self.map_tiles.area_running() {
            return;
        }
        if !self.map_tiles.is_ready() {
            self.show_pill("Map tiles are still loading", None, cx);
            return;
        }
        let plan = self.visible_area_plan();
        if plan.tiles.is_empty() {
            self.show_pill("Nothing to download here", None, cx);
            return;
        }
        if !self.area_armed {
            self.area_armed = true;
            cx.notify();
            return;
        }
        self.area_armed = false;
        if self.map_tiles.download_area(plan.tiles, cx) {
            self.show_pill("Downloading map area", None, cx);
        } else {
            self.show_pill("Map tiles are still loading", None, cx);
        }
        cx.notify();
    }

    /// Cancel on the progress row.
    pub(crate) fn cancel_area_download(&mut self, cx: &mut Context<Self>) {
        if let Some(text) = self.map_tiles.cancel_area(cx) {
            self.show_pill(text, None, cx);
            self.measure_tiles(cx);
        }
    }

    /// The area download ended: say how it went and refresh the cache size.
    pub(crate) fn on_area_finished(&mut self, text: String, cx: &mut Context<Self>) {
        self.show_pill(text, None, cx);
        self.measure_tiles(cx);
    }

    /// The progress row with its Cancel pill, while a download runs.
    pub(crate) fn area_progress_row(
        &mut self,
        theme: Theme,
        id: &'static str,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let area = self.map_tiles.area().filter(|a| !a.is_finished())?;
        let text = area.progress_text();
        let colors = theme::colors(theme);
        Some(
            div()
                .flex()
                .items_center()
                .gap(px(12.0))
                .px(px(20.0))
                .py(px(6.0))
                .child(
                    div()
                        .flex_1()
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(colors.secondary_label)
                        .child(text),
                )
                .child(
                    glass_pill(theme, "Cancel")
                        .id(id)
                        .on_click(cx.listener(|this, _, _, cx| this.cancel_area_download(cx))),
                )
                .into_any_element(),
        )
    }

    pub(crate) fn map_options_body(&mut self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let current = self.prefs.appearance;
        let choices = Appearance::ALL.into_iter().map(|choice| {
            glass_pill(theme, choice.label())
                .id(("map-appearance", choice as usize))
                .flex_1()
                .text_size(typography::SUBHEADLINE.size)
                .when(choice == current, |b| b.bg(colors.accent.opacity(0.25)))
                .on_click(cx.listener(move |this, _, _, cx| this.set_appearance(choice, cx)))
        });
        let appearance = div()
            .flex()
            .gap(px(8.0))
            .px(px(16.0))
            .children(choices.map(IntoElement::into_any_element));

        let running = self.map_tiles.area_running();
        let plan = self.visible_area_plan();
        let (title, subtitle): (SharedString, SharedString) = if running {
            (
                "Downloading map area".into(),
                "You can close this sheet.".into(),
            )
        } else if self.area_armed {
            (
                area_confirm_title(&plan).into(),
                "Tap again to start".into(),
            )
        } else {
            ("Download this area".into(), area_subtitle(&plan).into())
        };
        let download = list_row(theme, None, title, Some(subtitle), None)
            .id("map-area-download")
            .on_click(cx.listener(|this, _, _, cx| this.tap_area_download(cx)))
            .into_any_element();

        let centre = self.map.viewport.center;
        let zoom = display_zoom(&self.map.viewport);
        let copy_link = list_row(theme, None, "Copy map link", None, None)
            .id("map-copy-link")
            .on_click(cx.listener(move |this, _, _, cx| {
                cx.write_to_clipboard(gpui::ClipboardItem::new_string(osm_view_url(centre, zoom)));
                this.sheet.close();
                this.show_pill("Map link copied", None, cx);
                cx.notify();
            }))
            .into_any_element();
        let share_view = list_row(theme, None, "Share this view", None, None)
            .id("map-share-view")
            .on_click(cx.listener(move |this, _, _, cx| {
                this.sheet.close();
                this.share_out("Map view".into(), map_view_share_text(centre, zoom), cx);
                cx.notify();
            }))
            .into_any_element();
        let copy_centre = list_row(theme, None, "Copy centre as", None, None)
            .id("map-copy-centre")
            .on_click(cx.listener(move |this, _, _, cx| {
                this.open_copy_as("Map centre".into(), centre, cx);
            }))
            .into_any_element();
        let mut view_rows = vec![copy_link, share_view, copy_centre];
        if !self.saved.stops.is_empty() {
            let on = self.prefs.show_saved_on_map;
            view_rows.push(
                list_row(
                    theme,
                    None,
                    "Saved stops on map",
                    None,
                    Some(if on { "On" } else { "Off" }.into()),
                )
                .id("map-saved-toggle")
                .on_click(cx.listener(|this, _, _, cx| this.toggle_saved_on_map(cx)))
                .into_any_element(),
            );
        }

        let mut body = div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(Self::sheet_title(theme, "Map options"))
            .child(Self::footnote(theme, "Appearance"))
            .child(appearance)
            .child(section(theme, None, [download]))
            .child(Self::footnote(theme, "This view"))
            .child(section(theme, None, view_rows));
        if let Some(row) = self.area_progress_row(theme, "map-area-cancel", cx) {
            body = body.child(row);
        }
        body.child(Self::footnote(
            theme,
            "Downloaded areas work offline and count towards the map cache limit.",
        ))
        .into_any_element()
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use rovsti_geo::tiles::TileId;

    fn plan(n: usize, top_zoom: u8, capped: bool) -> AreaPlan {
        AreaPlan {
            tiles: (0..n as u32)
                .map(|x| TileId::new(14, x, 0).unwrap())
                .collect(),
            top_zoom,
            capped,
        }
    }

    #[test]
    fn the_appearance_maps_to_a_theme_override() {
        assert_eq!(theme_override(Appearance::Auto), None);
        assert_eq!(theme_override(Appearance::Light), Some(Theme::Light));
        assert_eq!(theme_override(Appearance::Dark), Some(Theme::Dark));
        assert_eq!(appearance_pill(Appearance::Dark), "Appearance: Dark");
    }

    #[test]
    fn the_subtitle_estimates_the_download() {
        assert_eq!(
            area_subtitle(&plan(900, 14, false)),
            "900 tiles, about 27 MB, up to zoom 14"
        );
        assert_eq!(
            area_subtitle(&plan(2500, 12, true)),
            "2500 tiles, about 75 MB, up to zoom 12. Zoom in for street detail"
        );
        assert_eq!(
            area_subtitle(&plan(1, 8, false)),
            "1 tile, about 1 MB, up to zoom 8"
        );
    }

    #[test]
    fn the_saved_toggle_names_the_new_state() {
        assert_eq!(saved_toggle_pill(true), "Saved stops shown");
        assert_eq!(saved_toggle_pill(false), "Saved stops hidden");
    }

    #[test]
    fn the_confirm_row_names_the_count() {
        assert_eq!(
            area_confirm_title(&plan(900, 14, false)),
            "Download 900 tiles"
        );
        assert_eq!(area_confirm_title(&plan(1, 8, false)), "Download 1 tile");
    }
}
