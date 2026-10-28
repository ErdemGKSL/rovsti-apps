//! stub
use gpui::{AnyElement, Context, IntoElement, Window, div};
use rovsti_ui::tokens::Theme;

use crate::PhoneApp;

impl PhoneApp {
    pub(crate) fn replies_opened(&mut self, _cx: &mut Context<Self>) {}
    pub(crate) fn replies_closed(&mut self, _cx: &mut Context<Self>) {}
    pub(crate) fn replies_tap(&mut self, _id: &str, _w: &mut Window, _cx: &mut Context<Self>) {}
    pub(crate) fn replies_body(
        &mut self,
        _t: Theme,
        _w: &Window,
        _cx: &mut Context<Self>,
    ) -> AnyElement {
        div().into_any_element()
    }
}
