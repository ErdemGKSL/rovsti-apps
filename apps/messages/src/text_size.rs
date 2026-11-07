//! Bubble text metrics for the "Text size" setting.

use gpui::{Pixels, px};
use rovsti_core::text_scale::ChatTextSize;

/// Font size and line height of message text. Captions, times and headers
/// stay fixed.
pub fn bubble_text(size: ChatTextSize) -> (Pixels, Pixels) {
    (px(size.scaled(16.0)), px(size.scaled(22.0)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_keeps_the_body_metrics() {
        assert_eq!(bubble_text(ChatTextSize::Default), (px(16.0), px(22.0)));
    }

    #[test]
    fn sizes_grow_in_order() {
        let sizes: Vec<f32> = ChatTextSize::ALL
            .iter()
            .map(|s| f32::from(bubble_text(*s).0))
            .collect();
        assert!(sizes.windows(2).all(|w| w[0] < w[1]));
        let lines: Vec<f32> = ChatTextSize::ALL
            .iter()
            .map(|s| f32::from(bubble_text(*s).1))
            .collect();
        assert!(lines.windows(2).all(|w| w[0] < w[1]));
    }
}
