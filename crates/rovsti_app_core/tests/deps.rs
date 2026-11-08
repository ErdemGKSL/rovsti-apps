//! The crate must stay free of UI and platform dependencies: the Kotlin build
//! links it, and a GPUI or JNI dependency would drag a UI toolkit along.

const FORBIDDEN: &[&str] = &[
    "gpui",
    "gpui_android",
    "gpui_effects",
    "gpui_icons",
    "gpui_platform",
    "gpui_wgpu",
    "liquid_glass_kit",
    "rovsti_ui",
    "rovsti_wa_ui",
    "rovsti_android",
    "jni",
];

#[test]
fn cargo_toml_names_no_ui_or_platform_crate() {
    let manifest = include_str!("../Cargo.toml");
    let mut in_deps = false;
    for line in manifest.lines() {
        let line = line.trim();
        if line.starts_with('[') {
            in_deps = line.contains("dependencies");
            continue;
        }
        if !in_deps || line.is_empty() || line.starts_with('#') {
            continue;
        }
        let name = line
            .split(['=', '.', ' '])
            .next()
            .unwrap_or_default()
            .trim();
        assert!(
            !FORBIDDEN.contains(&name),
            "rovsti_app_core must not depend on `{name}`"
        );
    }
}
