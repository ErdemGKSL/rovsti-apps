//! Validates `shaders/liquid_glass.wgsl` the way the renderer will see it:
//! spliced into gpui-fusion's backdrop wrapper, parsed and validated by naga.
//! Run after every shader edit: `cargo test --test shader_validates`.
//! The rest variant (no press glow) and the grid variant are derived from the
//! same file, so they are validated too.

use gpui::{BackdropShader, compose_backdrop_shader_wgsl};
use liquid_glass_kit::glass::{glass_grid_shader, glass_shader, glass_shader_rest};

fn validate(name: &str, shader: &BackdropShader) {
    let source = compose_backdrop_shader_wgsl(shader);
    let module = naga::front::wgsl::parse_str(&source)
        .unwrap_or_else(|e| panic!("{name}: WGSL parse error:\n{}", e.emit_to_string(&source)));
    naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::all(),
    )
    .validate(&module)
    .unwrap_or_else(|e| panic!("{name}: WGSL validation error: {e:?}"));
}

#[test]
fn liquid_glass_wgsl_validates() {
    validate(
        "template",
        &BackdropShader::wgsl(include_str!("../shaders/liquid_glass.wgsl")),
    );
}

#[test]
fn every_variant_validates() {
    validate("full", &glass_shader());
    validate("rest", &glass_shader_rest());
    validate("grid", &glass_grid_shader());
}

#[test]
fn variants_are_distinct_shaders() {
    let ids = [
        glass_shader().id(),
        glass_shader_rest().id(),
        glass_grid_shader().id(),
    ];
    assert!(ids[0] != ids[1] && ids[1] != ids[2] && ids[0] != ids[2]);
}
