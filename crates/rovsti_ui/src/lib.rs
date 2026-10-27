//! Shared liquid-glass components for the Rovsti apps.
//!
//! Product decisions from the liquid-glass-gpui skill's setup questions live
//! here as constants so every app makes the same choice and it is easy to
//! flip later.

pub mod components;
pub mod motion;
pub mod storage;
pub mod tab_bar;

pub use gpui_icons::{GpuiIcons as Icon, IconAssets};
pub use liquid_glass_kit::{glass, motion_tokens, spring, tokens};

/// Phones don't resize freely; rotation and foldables reflow instantly.
pub const ANIMATED_RESIZE: bool = false;
/// Follow Android's night-mode setting (`Window::appearance`).
pub const FOLLOW_SYSTEM_THEME: bool = true;

use gpui::Window;
use liquid_glass_kit::tokens::Theme;

/// The theme the window should paint with right now.
pub fn current_theme(window: &Window) -> Theme {
    if FOLLOW_SYSTEM_THEME {
        Theme::from_appearance(window.appearance())
    } else {
        Theme::Light
    }
}
