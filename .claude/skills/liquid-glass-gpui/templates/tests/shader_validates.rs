//! Validates `shaders/liquid_glass.wgsl` the way the renderer will see it:
//! spliced into gpui-fusion's backdrop wrapper, parsed and validated by naga.
//! Run after every shader edit: `cargo test --test shader_validates`.

use gpui::{BackdropShader, compose_backdrop_shader_wgsl};

#[test]
fn liquid_glass_wgsl_validates() {
    let shader = BackdropShader::wgsl(include_str!("../shaders/liquid_glass.wgsl"));
    let source = compose_backdrop_shader_wgsl(&shader);
    let module = naga::front::wgsl::parse_str(&source)
        .unwrap_or_else(|e| panic!("WGSL parse error:\n{}", e.emit_to_string(&source)));
    naga::valid::Validator::new(
        naga::valid::ValidationFlags::all(),
        naga::valid::Capabilities::all(),
    )
    .validate(&module)
    .unwrap_or_else(|e| panic!("WGSL validation error: {e:?}"));
}
