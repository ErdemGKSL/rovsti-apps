
// ---- Grid entry (appended to the rest variant by `glass_grid_shader`) -------
//
// One effect for a regular grid of equal surfaces. Uniforms are those of the
// first cell (slots 0 to 5 as above) plus:
//   slot0.zw  cell size (device px)        slot0.xy  centre of cell (0, 0)
//   slot6.z   bit mask of cells NOT painted by this effect (row major)
//   slot6.w   columns + 16 * rows
//   slot7.xy  distance between cell centres, per axis (device px)
// Each fragment belongs to the nearest cell and is shaded exactly as that cell
// would be on its own, so the output matches painting the cells one by one.
fn backdrop_effect(input: BackdropInput, params: BackdropParams) -> vec4<f32> {
    let first = params.slots[0];
    let extra = params.slots[6];
    let pitch = params.slots[7].xy;
    let cols = f32(u32(extra.w) & 15u);
    let rows = f32(u32(extra.w) >> 4u);

    let cell = clamp(
        round((input.position - first.xy) / pitch),
        vec2<f32>(0.0),
        vec2<f32>(cols - 1.0, rows - 1.0),
    );
    let index = u32(cell.y * cols + cell.x);
    if (((u32(extra.z) >> index) & 1u) != 0u) {
        return vec4<f32>(0.0);
    }

    // Far from this cell's box nothing of the surface (not even its dark
    // outline) can reach the fragment: skip the sampling work.
    let centre = first.xy + cell * pitch;
    let reach = abs(input.position - centre) - first.zw * 0.5;
    if (length(max(reach, vec2<f32>(0.0))) > 6.0 + params.slots[3].w) {
        return vec4<f32>(0.0);
    }

    var shaded = params;
    shaded.slots[0] = vec4<f32>(centre, first.zw);
    shaded.slots[6] = vec4<f32>(extra.x, extra.y, 0.0, 0.0);
    shaded.slots[7] = vec4<f32>(0.0);
    return lg_shade(input, shaded);
}
