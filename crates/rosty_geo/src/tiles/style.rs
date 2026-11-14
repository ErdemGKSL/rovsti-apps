//! Colour table for the two map styles.
//!
//! Colours and line widths are copied from the versatiles-style project
//! (https://github.com/versatiles-org/versatiles-style), styles `colorful`
//! (light theme) and `eclipse` (dark theme), as served at
//! `https://tiles.versatiles.org/assets/styles/{colorful,eclipse}/style.json`
//! on 2026-11-06. Licence: Unlicense (public domain) at the time of writing;
//! see upstream for the authoritative text. Map data is (c) OpenStreetMap
//! contributors (ODbL).

use super::shortbread::{DrawClass, LandKind, StreetKind};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MapStyle {
    /// Light theme.
    Colorful,
    /// Dark theme.
    Eclipse,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rgba8 {
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: f32,
}

impl Rgba8 {
    const fn rgb(r: u8, g: u8, b: u8) -> Self {
        Self { r, g, b, a: 1.0 }
    }

    const fn with_alpha(self, a: f32) -> Self {
        Self { a, ..self }
    }

    /// `0xRRGGBBAA`, as `gpui::rgba` takes it.
    pub fn to_u32(self) -> u32 {
        let a = (self.a.clamp(0.0, 1.0) * 255.0).round() as u32;
        (u32::from(self.r) << 24) | (u32::from(self.g) << 16) | (u32::from(self.b) << 8) | a
    }
}

/// How one class is painted. Fills use `color` (and `casing` as an outline
/// when set); lines use `color` with `casing` drawn first, wider.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Paint {
    pub color: Rgba8,
    pub casing: Option<Rgba8>,
    /// Line width in px at display zoom 14 (0 for fills).
    pub width14: f32,
}

const fn c(r: u8, g: u8, b: u8) -> Rgba8 {
    Rgba8::rgb(r, g, b)
}

fn fill(color: Rgba8) -> Paint {
    Paint {
        color,
        casing: None,
        width14: 0.0,
    }
}

fn line(color: Rgba8, casing: Option<Rgba8>, width14: f32) -> Paint {
    Paint {
        color,
        casing,
        width14,
    }
}

impl MapStyle {
    /// Pick by "is the app theme dark".
    pub fn for_dark(dark: bool) -> Self {
        if dark { Self::Eclipse } else { Self::Colorful }
    }

    fn pick(self, light: Rgba8, dark: Rgba8) -> Rgba8 {
        match self {
            Self::Colorful => light,
            Self::Eclipse => dark,
        }
    }

    pub fn background(self) -> Rgba8 {
        self.pick(c(248, 244, 240), c(40, 39, 37))
    }

    pub fn paint(self, class: DrawClass) -> Paint {
        let p = |l: Rgba8, d: Rgba8| self.pick(l, d);
        match class {
            DrawClass::Ocean | DrawClass::Water => fill(p(c(191, 217, 242), c(12, 28, 42))),
            DrawClass::Glacier => fill(p(c(255, 255, 255), c(70, 70, 70))),
            DrawClass::Land(k) => fill(self.land(k)),
            DrawClass::PierArea => fill(self.background()),
            DrawClass::PedestrianArea => fill(p(c(251, 235, 255), c(48, 39, 50))),
            DrawClass::Building => Paint {
                color: p(c(242, 234, 226), c(49, 44, 39)),
                casing: Some(p(c(223, 219, 215), c(57, 55, 52))),
                width14: 0.0,
            },
            DrawClass::Waterway { .. } => line(p(c(166, 202, 237), c(22, 44, 64)), None, 2.0),
            DrawClass::Street { kind, tunnel } => {
                let paint = self.street(kind);
                if tunnel { halve(paint) } else { paint }
            }
            DrawClass::Rail { tunnel } => {
                let paint = line(
                    p(c(200, 207, 214), c(50, 55, 59)),
                    Some(p(c(177, 187, 196), c(71, 78, 84))),
                    1.5,
                );
                if tunnel { halve(paint) } else { paint }
            }
            DrawClass::Boundary { .. } => line(p(c(166, 166, 200), c(87, 87, 112)), None, 1.5),
        }
    }

    fn land(self, k: LandKind) -> Rgba8 {
        let (l, d) = match k {
            LandKind::Commercial => (c(248, 238, 239), c(46, 40, 41)),
            LandKind::Industrial => (c(250, 244, 225), c(43, 39, 28)),
            LandKind::Residential => (c(245, 241, 237), c(43, 40, 38)),
            LandKind::Agriculture => (c(233, 242, 206), c(41, 45, 23)),
            LandKind::Waste => (c(219, 214, 189), c(62, 59, 43)),
            LandKind::Park => (
                c(205, 217, 169).with_alpha(0.5),
                c(56, 63, 31).with_alpha(0.5),
            ),
            LandKind::Garden => (c(205, 217, 169), c(56, 63, 31)),
            LandKind::Burial => (c(224, 228, 221), c(49, 51, 47)),
            LandKind::Leisure => (c(231, 237, 222), c(43, 47, 37)),
            LandKind::Rock => (c(242, 242, 230), c(42, 41, 34)),
            LandKind::Forest => (c(203, 229, 184), c(43, 59, 29)),
            LandKind::Grass => (c(209, 229, 195), c(44, 57, 35)),
            LandKind::Vegetation => (c(230, 238, 199), c(44, 48, 23)),
            LandKind::Sand => (c(242, 242, 206), c(44, 43, 20)),
            LandKind::Wetland => (c(211, 230, 219), c(42, 54, 47)),
        };
        self.pick(l, d)
    }

    fn street(self, k: StreetKind) -> Paint {
        let p = |l: Rgba8, d: Rgba8| self.pick(l, d);
        let white = |w: f32| {
            line(
                p(c(255, 255, 255), c(82, 82, 82)),
                Some(p(c(207, 205, 202), c(53, 52, 50))),
                w,
            )
        };
        match k {
            StreetKind::Motorway => line(
                p(c(255, 204, 136), c(105, 71, 16)),
                Some(p(c(233, 172, 119), c(90, 49, 4))),
                4.0,
            ),
            StreetKind::Arterial => line(
                p(c(255, 238, 170), c(105, 93, 43)),
                Some(p(c(233, 172, 119), c(90, 49, 4))),
                5.0,
            ),
            StreetKind::Tertiary => white(3.0),
            StreetKind::Minor => white(2.0),
            StreetKind::Service => line(
                p(c(247, 247, 247), c(87, 87, 87)),
                Some(p(c(221, 220, 218), c(62, 61, 60))),
                1.5,
            ),
            StreetKind::Pedestrian => line(
                p(c(251, 235, 255), c(48, 39, 50)),
                Some(p(c(207, 205, 202), c(53, 52, 50))),
                1.5,
            ),
            StreetKind::Track => white(1.5),
            StreetKind::Path => line(p(c(213, 200, 217), c(79, 71, 81)), None, 1.0),
            StreetKind::Cycleway => line(p(c(203, 212, 217), c(69, 73, 77)), None, 1.0),
        }
    }
}

/// Tunnels are drawn like their class at half alpha.
fn halve(mut p: Paint) -> Paint {
    p.color.a *= 0.5;
    if let Some(c) = p.casing.as_mut() {
        c.a *= 0.5;
    }
    p
}

/// Line width in px at `display_zoom`: `w14 * 2^(clamp(dz - 14, -3, 2))`, at
/// least 0.5 px.
pub fn line_width(width14: f32, display_zoom: f64) -> f32 {
    let scale = 2f64.powf((display_zoom - 14.0).clamp(-3.0, 2.0));
    (f64::from(width14) * scale).max(0.5) as f32
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    const LANDS: [LandKind; 15] = [
        LandKind::Commercial,
        LandKind::Industrial,
        LandKind::Residential,
        LandKind::Agriculture,
        LandKind::Waste,
        LandKind::Park,
        LandKind::Garden,
        LandKind::Burial,
        LandKind::Leisure,
        LandKind::Rock,
        LandKind::Forest,
        LandKind::Grass,
        LandKind::Vegetation,
        LandKind::Sand,
        LandKind::Wetland,
    ];
    const STREETS: [StreetKind; 9] = [
        StreetKind::Path,
        StreetKind::Cycleway,
        StreetKind::Track,
        StreetKind::Pedestrian,
        StreetKind::Service,
        StreetKind::Minor,
        StreetKind::Tertiary,
        StreetKind::Arterial,
        StreetKind::Motorway,
    ];

    fn all_classes() -> Vec<DrawClass> {
        let mut v = vec![
            DrawClass::Ocean,
            DrawClass::Water,
            DrawClass::Glacier,
            DrawClass::PierArea,
            DrawClass::PedestrianArea,
            DrawClass::Building,
        ];
        v.extend(LANDS.map(DrawClass::Land));
        for small in [false, true] {
            v.push(DrawClass::Waterway { small });
        }
        for kind in STREETS {
            for tunnel in [false, true] {
                v.push(DrawClass::Street { kind, tunnel });
            }
        }
        for tunnel in [false, true] {
            v.push(DrawClass::Rail { tunnel });
        }
        for country in [false, true] {
            v.push(DrawClass::Boundary { country });
        }
        v
    }

    #[test]
    fn every_class_has_a_paint_in_both_styles() {
        for style in [MapStyle::Colorful, MapStyle::Eclipse] {
            for class in all_classes() {
                let p = style.paint(class);
                assert!(p.color.a > 0.0 && p.color.a <= 1.0, "{class:?}");
                let is_line = class.shape() == super::super::shortbread::Shape::Line;
                assert_eq!(p.width14 > 0.0, is_line, "{class:?}");
            }
        }
    }

    fn rgb(c: Rgba8) -> (u8, u8, u8) {
        (c.r, c.g, c.b)
    }

    #[test]
    fn spot_values_match_the_table() {
        let (l, d) = (MapStyle::Colorful, MapStyle::Eclipse);
        assert_eq!(rgb(l.background()), (248, 244, 240));
        assert_eq!(rgb(d.background()), (40, 39, 37));
        assert_eq!(rgb(l.paint(DrawClass::Ocean).color), (191, 217, 242));
        assert_eq!(rgb(d.paint(DrawClass::Ocean).color), (12, 28, 42));
        let mw = DrawClass::Street {
            kind: StreetKind::Motorway,
            tunnel: false,
        };
        assert_eq!(rgb(l.paint(mw).color), (255, 204, 136));
        assert_eq!(rgb(d.paint(mw).color), (105, 71, 16));
        assert_eq!(rgb(l.paint(mw).casing.unwrap()), (233, 172, 119));
        assert_eq!(rgb(d.paint(mw).casing.unwrap()), (90, 49, 4));
        assert_eq!(l.paint(mw).width14, 4.0);
        assert_eq!(rgb(l.paint(DrawClass::Building).color), (242, 234, 226));
        assert_eq!(rgb(d.paint(DrawClass::Building).color), (49, 44, 39));
        assert_eq!(
            rgb(l.paint(DrawClass::Building).casing.unwrap()),
            (223, 219, 215)
        );
        let park = l.paint(DrawClass::Land(LandKind::Park)).color;
        assert_eq!((rgb(park), park.a), ((205, 217, 169), 0.5));
    }

    #[test]
    fn eclipse_is_darker() {
        let luma = |c: Rgba8| u32::from(c.r) + u32::from(c.g) + u32::from(c.b);
        assert!(luma(MapStyle::Eclipse.background()) < luma(MapStyle::Colorful.background()));
        assert_eq!(MapStyle::for_dark(true), MapStyle::Eclipse);
        assert_eq!(MapStyle::for_dark(false), MapStyle::Colorful);
    }

    #[test]
    fn tunnel_halves_alpha() {
        let k = StreetKind::Tertiary;
        let on = MapStyle::Colorful.paint(DrawClass::Street {
            kind: k,
            tunnel: true,
        });
        let off = MapStyle::Colorful.paint(DrawClass::Street {
            kind: k,
            tunnel: false,
        });
        assert_eq!(on.color.a, 0.5);
        assert_eq!(on.casing.unwrap().a, 0.5);
        assert_eq!(rgb(on.color), rgb(off.color));
        assert_eq!(off.color.a, 1.0);
    }

    #[test]
    fn to_u32_packs_rrggbbaa() {
        assert_eq!(c(255, 204, 136).to_u32(), 0xFFCC88FF);
        assert_eq!(c(1, 2, 3).with_alpha(0.5).to_u32(), 0x01020380);
        assert_eq!(c(0, 0, 0).with_alpha(0.0).to_u32(), 0);
    }

    #[test]
    fn line_width_scales_and_clamps() {
        assert_eq!(line_width(4.0, 14.0), 4.0);
        assert_eq!(line_width(4.0, 15.0), 8.0);
        assert_eq!(line_width(4.0, 20.0), 16.0); // clamped at +2
        assert_eq!(line_width(4.0, 13.0), 2.0);
        assert_eq!(line_width(4.0, 3.0), 0.5); // 4/8 = 0.5, clamped at -3
        assert_eq!(line_width(1.0, 11.0), 0.5); // floor
        assert_eq!(line_width(1.0, 0.0), 0.5);
    }
}
