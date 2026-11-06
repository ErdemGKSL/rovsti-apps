//! Set, replace and remove a contact's photo (utility U10).
//!
//! A small menu (Choose new photo or Add photo, Remove photo) and the confirm
//! step for removal, drawn above the photo viewer and the sheets. The menu opens
//! from the viewer's Edit button, or straight from the Contact sheet's avatar when
//! the contact has no photo. The picking itself is `backup_ui::pick_photo_for`
//! and the hand-back `backup_ui::photo_picked`.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, MouseButton, ParentElement, Role,
    SharedString, StatefulInteractiveElement, Styled, Window, div, px,
};
use rovsti_ui::{
    Icon,
    glass::{GlassSpec, glass},
    motion::press_scale,
    motion_tokens::scale,
    theme::{self, call_colors},
    tokens::{Theme, shadow, typography},
    touch::touch_surface,
};

use crate::PhoneApp;

/// Every control id of the menu starts with this. It starts with `sheet-` so a
/// tap works over the Contact sheet, and the photo viewer lets it through too.
pub(crate) const PREFIX: &str = "sheet-pe-";

/// What a row of the menu or the confirm step does.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Item {
    /// Choose new photo / Add photo.
    Choose,
    /// Remove photo (asks first).
    Remove,
    /// Confirm the removal.
    Yes,
    /// Back out of the removal.
    No,
}

impl Item {
    pub(crate) fn id(self) -> &'static str {
        match self {
            Self::Choose => "sheet-pe-choose",
            Self::Remove => "sheet-pe-remove",
            Self::Yes => "sheet-pe-yes",
            Self::No => "sheet-pe-no",
        }
    }

    pub(crate) fn from_id(id: &str) -> Option<Self> {
        [Self::Choose, Self::Remove, Self::Yes, Self::No]
            .into_iter()
            .find(|item| item.id() == id)
    }
}

/// The rows of the menu: Remove only when the contact has a photo of its own.
pub(crate) fn menu_items(has_photo: bool) -> &'static [Item] {
    if has_photo {
        &[Item::Choose, Item::Remove]
    } else {
        &[Item::Choose]
    }
}

/// The label of the pick row.
pub(crate) fn choose_label(has_photo: bool) -> &'static str {
    if has_photo {
        "Choose new photo"
    } else {
        "Add photo"
    }
}

/// The title of the removal confirm.
pub(crate) fn remove_confirm_title(name: &str) -> String {
    format!("Remove photo of {name}?")
}

/// The line under the confirm title.
pub(crate) const REMOVE_NOTE: &str =
    "The photo is removed from this contact. This can't be undone.";

/// The open menu.
pub(crate) struct PhotoEdit {
    pub contact_id: i64,
    pub name: SharedString,
    /// The contact has a photo of its own to replace or remove.
    pub has_photo: bool,
    /// The confirm step of Remove is showing.
    pub confirming: bool,
}

impl PhoneApp {
    /// Opens the menu for `contact_id` (over the viewer or the Contact sheet).
    pub(crate) fn open_photo_menu(
        &mut self,
        contact_id: i64,
        name: SharedString,
        has_photo: bool,
        cx: &mut Context<Self>,
    ) {
        self.photo_edit = Some(PhotoEdit {
            contact_id,
            name,
            has_photo,
            confirming: false,
        });
        self.presses.release_all();
        cx.notify();
    }

    pub(crate) fn close_photo_menu(&mut self, cx: &mut Context<Self>) {
        if self.photo_edit.take().is_some() {
            self.presses.release_all();
            cx.notify();
        }
    }

    /// A row of the menu was pressed.
    pub(crate) fn photo_edit_act(&mut self, item: Item, cx: &mut Context<Self>) {
        let Some(edit) = &mut self.photo_edit else {
            return;
        };
        let id = edit.contact_id;
        match item {
            Item::Choose => {
                self.photo_edit = None;
                self.pick_photo_for(id, cx);
            }
            Item::Remove => edit.confirming = true,
            Item::No => self.photo_edit = None,
            Item::Yes => {
                self.photo_edit = None;
                self.remove_photo_of(id, cx);
            }
        }
        self.presses.release_all();
        cx.notify();
    }

    fn photo_edit_row(
        &self,
        theme: Theme,
        item: Item,
        label: &'static str,
        icon: Icon,
        danger: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let ink = if danger {
            call_colors(theme).danger
        } else {
            colors.label
        };
        let id = item.id();
        let press = self.presses.value(id).clamp(0.0, 1.0);
        div()
            .id(id)
            .role(Role::Button)
            .aria_label(label)
            .relative()
            .h(px(54.0 * press_scale(press, scale::PRESS)))
            .px(px(20.0))
            .rounded_full()
            .bg(gpui::Hsla {
                a: 0.9 * press,
                ..colors.fill
            })
            .flex()
            .items_center()
            .gap(px(14.0))
            .child(gpui::svg().path(icon.path()).size(px(22.0)).text_color(ink))
            .child(
                div()
                    .text_size(typography::BODY.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(ink)
                    .child(label),
            )
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, _, cx| {
                    this.presses.down(id);
                    if let Some(item) = Item::from_id(id) {
                        this.photo_edit_act(item, cx);
                    }
                }),
            )
            .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
            .into_any_element()
    }

    /// The menu, above the viewer and under the notice pill.
    pub(crate) fn photo_edit_overlay(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let edit = self.photo_edit.as_ref()?;
        let colors = theme::colors(theme);
        let mut card = div().flex().flex_col().gap(px(4.0)).p(px(10.0));
        if edit.confirming {
            card = card
                .child(
                    div()
                        .px(px(12.0))
                        .pt(px(8.0))
                        .text_size(typography::TITLE3.size)
                        .font_weight(typography::TITLE3.weight)
                        .text_color(colors.label)
                        .child(remove_confirm_title(&edit.name)),
                )
                .child(
                    div()
                        .px(px(12.0))
                        .pb(px(6.0))
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(colors.secondary_label)
                        .child(REMOVE_NOTE),
                )
                .child(self.photo_edit_row(
                    theme,
                    Item::Yes,
                    "Remove",
                    Icon::LucideTrash2,
                    true,
                    cx,
                ))
                .child(self.photo_edit_row(theme, Item::No, "Cancel", Icon::LucideX, false, cx));
        } else {
            for item in menu_items(edit.has_photo) {
                card = card.child(match item {
                    Item::Remove => self.photo_edit_row(
                        theme,
                        Item::Remove,
                        "Remove photo",
                        Icon::LucideTrash2,
                        true,
                        cx,
                    ),
                    _ => self.photo_edit_row(
                        theme,
                        Item::Choose,
                        choose_label(edit.has_photo),
                        Icon::LucideImagePlus,
                        false,
                        cx,
                    ),
                });
            }
        }
        let bottom = crate::safe_bottom(window) + 16.0;
        let sheet = glass(GlassSpec::chrome(theme))
            .occlude()
            .absolute()
            .bottom(px(bottom))
            .left(px(16.0))
            .right(px(16.0))
            .rounded(px(28.0))
            .shadow(shadow::glass(theme.is_dark()))
            .child(card);
        Some(
            div()
                .id("photo-edit")
                .role(Role::Dialog)
                .aria_label("Photo options")
                .absolute()
                .inset_0()
                .occlude()
                .bg(gpui::Hsla {
                    a: 0.4,
                    ..gpui::black()
                })
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(|this, _, _, cx| this.close_photo_menu(cx)),
                )
                .child(sheet)
                .into_any_element(),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_round_trip_and_stay_in_the_prefix() {
        for item in [Item::Choose, Item::Remove, Item::Yes, Item::No] {
            assert_eq!(Item::from_id(item.id()), Some(item));
            assert!(item.id().starts_with(PREFIX));
        }
        assert_eq!(Item::from_id("sheet-pe-"), None);
        assert_eq!(Item::from_id("sheet-pe-other"), None);
        assert_eq!(Item::from_id("pv-save"), None);
    }

    #[test]
    fn the_menu_offers_remove_only_with_a_photo() {
        assert_eq!(menu_items(true), &[Item::Choose, Item::Remove]);
        assert_eq!(menu_items(false), &[Item::Choose]);
        assert_eq!(choose_label(true), "Choose new photo");
        assert_eq!(choose_label(false), "Add photo");
    }

    #[test]
    fn the_confirm_names_the_person() {
        assert_eq!(remove_confirm_title("Ada"), "Remove photo of Ada?");
        assert!(REMOVE_NOTE.contains("can't be undone"));
    }
}
