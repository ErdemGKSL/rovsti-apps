//! Liquid-glass kit for gpui-fusion: copy this crate (or its modules) into a
//! project. `spring` and `motion_tokens` have no GPUI dependency and also
//! work on upstream GPUI; `glass` needs gpui-fusion's backdrop shaders.

pub mod glass;
pub mod motion_tokens;
pub mod spring;
pub mod tokens;
