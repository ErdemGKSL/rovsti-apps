//! Shared liquid-glass components for the Rovsti apps.
//!
//! Product decisions from the liquid-glass-gpui skill's setup questions live
//! here as constants so every app makes the same choice and it is easy to
//! flip later.

pub mod chips;
pub mod components;
pub mod field;
pub mod haptics;
pub mod layout;
pub mod moments;
pub mod motion;
pub mod notice_pill;
pub mod page;
pub mod perf;
pub mod row;
pub mod sheet;
#[cfg(feature = "shots")]
pub mod shots;
pub mod storage;
pub mod tab_bar;
pub mod theme;
pub mod touch;

pub use gpui_icons::{GpuiIcons as Icon, IconAssets};
pub use liquid_glass_kit::{glass, motion_tokens, spring, tokens};

/// Phones don't resize freely; rotation and foldables reflow instantly.
pub const ANIMATED_RESIZE: bool = false;
/// Follow Android's night-mode setting (`Window::appearance`).
pub const FOLLOW_SYSTEM_THEME: bool = true;

use gpui::Window;
use liquid_glass_kit::tokens::Theme;

/// 0 = follow the system, 1 = light, 2 = dark. Set only by desktop previews
/// (`ROVSTI_PREVIEW_THEME_FLIP`) and tests; one relaxed load per call.
static THEME_OVERRIDE: std::sync::atomic::AtomicU8 = std::sync::atomic::AtomicU8::new(0);

/// Forces a theme (`Some`) or goes back to the system's (`None`).
pub fn set_theme_override(theme: Option<Theme>) {
    let value = match theme {
        None => 0,
        Some(Theme::Light) => 1,
        Some(_) => 2,
    };
    THEME_OVERRIDE.store(value, std::sync::atomic::Ordering::Relaxed);
}

/// The theme the window should paint with right now.
pub fn current_theme(window: &Window) -> Theme {
    match THEME_OVERRIDE.load(std::sync::atomic::Ordering::Relaxed) {
        1 => return Theme::Light,
        2 => return Theme::Dark,
        _ => {}
    }
    if FOLLOW_SYSTEM_THEME {
        Theme::from_appearance(window.appearance())
    } else {
        Theme::Light
    }
}
