//! Shortbread 1.0 layers -> the small set of draw classes the map paints.
//!
//! Pure: no I/O. The mapping and the per-class minimum zoom follow the
//! versatiles-style "colorful" style (see `style.rs` for the source).

use super::mvt::{MvtFeature, MvtLayer};

/// Land use / land cover kinds, each with its own colour.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum LandKind {
    Commercial,
    Industrial,
    Residential,
    Agriculture,
    Waste,
    Park,
    Garden,
    Burial,
    Leisure,
    Rock,
    Forest,
    Grass,
    Vegetation,
    Sand,
    Wetland,
}

/// Street kinds, lowest painted first.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum StreetKind {
    Path,
    Cycleway,
    Track,
    Pedestrian,
    Service,
    Minor,
    Tertiary,
    /// trunk, primary and secondary (and their links)
    Arterial,
    Motorway,
}

/// Whether a class is filled (polygons) or stroked (lines).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Shape {
    Fill,
    Line,
}

/// What a feature is drawn as. Declaration order is the paint order, so the
/// derived `Ord` sorts classes bottom to top.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum DrawClass {
    Ocean,
    Water,
    Glacier,
    Land(LandKind),
    PierArea,
    PedestrianArea,
    Building,
    Waterway { small: bool },
    Street { kind: StreetKind, tunnel: bool },
    Rail { tunnel: bool },
    Boundary { country: bool },
}

impl DrawClass {
    pub fn shape(self) -> Shape {
        match self {
            Self::Ocean
            | Self::Water
            | Self::Glacier
            | Self::Land(_)
            | Self::PierArea
            | Self::PedestrianArea
            | Self::Building => Shape::Fill,
            Self::Waterway { .. }
            | Self::Street { .. }
            | Self::Rail { .. }
            | Self::Boundary { .. } => Shape::Line,
        }
    }

    /// The first tile zoom at which this class is drawn.
    pub fn min_zoom(self) -> u8 {
        match self {
            Self::Ocean => 0,
            Self::Water | Self::Glacier => 4,
            Self::Land(k) => match k {
                LandKind::Forest => 7,
                LandKind::Commercial
                | LandKind::Industrial
                | LandKind::Residential
                | LandKind::Agriculture
                | LandKind::Waste
                | LandKind::Sand => 10,
                LandKind::Burial => 13,
                LandKind::Park
                | LandKind::Garden
                | LandKind::Leisure
                | LandKind::Rock
                | LandKind::Grass
                | LandKind::Vegetation
                | LandKind::Wetland => 11,
            },
            Self::PierArea => 12,
            Self::PedestrianArea | Self::Building => 14,
            Self::Waterway { small } => {
                if small {
                    14
                } else {
                    9
                }
            }
            Self::Street { kind, .. } => match kind {
                StreetKind::Motorway => 5,
                StreetKind::Arterial => 8,
                StreetKind::Tertiary => 10,
                StreetKind::Minor => 12,
                StreetKind::Pedestrian | StreetKind::Path | StreetKind::Cycleway => 13,
                StreetKind::Service | StreetKind::Track => 14,
            },
            Self::Rail { .. } => 8,
            Self::Boundary { country } => {
                if country {
                    0
                } else {
                    7
                }
            }
        }
    }
}

fn land_kind(kind: &str) -> Option<LandKind> {
    Some(match kind {
        "commercial" | "retail" => LandKind::Commercial,
        "industrial" | "quarry" | "railway" => LandKind::Industrial,
        "garages" | "residential" => LandKind::Residential,
        "farmland"
        | "farmyard"
        | "greenhouse_horticulture"
        | "orchard"
        | "plant_nursery"
        | "vineyard" => LandKind::Agriculture,
        "landfill" => LandKind::Waste,
        "park" | "village_green" | "recreation_ground" => LandKind::Park,
        "allotments" | "garden" => LandKind::Garden,
        "cemetery" | "grave_yard" => LandKind::Burial,
        "miniature_golf" | "playground" | "golf_course" => LandKind::Leisure,
        "bare_rock" | "scree" | "shingle" => LandKind::Rock,
        "forest" => LandKind::Forest,
        "grass" | "grassland" | "meadow" | "wet_meadow" => LandKind::Grass,
        "heath" | "scrub" => LandKind::Vegetation,
        "beach" | "sand" => LandKind::Sand,
        "bog" | "marsh" | "string_bog" | "swamp" => LandKind::Wetland,
        _ => return None,
    })
}

enum StreetClass {
    Road(StreetKind),
    Rail,
}

fn street_class(kind: &str) -> Option<StreetClass> {
    // `*_link` ramps draw like their parent kind.
    let base = kind.strip_suffix("_link").unwrap_or(kind);
    Some(match base {
        "motorway" => StreetClass::Road(StreetKind::Motorway),
        "trunk" | "primary" | "secondary" => StreetClass::Road(StreetKind::Arterial),
        "tertiary" => StreetClass::Road(StreetKind::Tertiary),
        "residential" | "unclassified" | "living_street" => StreetClass::Road(StreetKind::Minor),
        "service" | "busway" | "bus_guideway" => StreetClass::Road(StreetKind::Service),
        "pedestrian" => StreetClass::Road(StreetKind::Pedestrian),
        "track" => StreetClass::Road(StreetKind::Track),
        "footway" | "steps" | "path" => StreetClass::Road(StreetKind::Path),
        "cycleway" => StreetClass::Road(StreetKind::Cycleway),
        "rail" | "light_rail" | "subway" | "tram" | "narrow_gauge" => StreetClass::Rail,
        _ => return None,
    })
}

/// Layer name + feature properties -> draw class. `None` for layers and kinds
/// that are not drawn (labels, pois, addresses, place labels, public transport,
/// aerialways, ferries, bridges, sites, maritime boundaries, unknown kinds).
pub fn classify(layer: &MvtLayer, f: &MvtFeature) -> Option<DrawClass> {
    let kind = layer.str_of(f, "kind");
    match layer.name.as_str() {
        "ocean" => Some(DrawClass::Ocean),
        "water_polygons" => match kind? {
            "water" | "river" | "reservoir" | "basin" | "dock" => Some(DrawClass::Water),
            "glacier" => Some(DrawClass::Glacier),
            _ => None,
        },
        "water_lines" => match kind? {
            "river" | "canal" => Some(DrawClass::Waterway { small: false }),
            "stream" | "ditch" => Some(DrawClass::Waterway { small: true }),
            _ => None,
        },
        "land" => land_kind(kind?).map(DrawClass::Land),
        "pier_polygons" | "dam_polygons" => Some(DrawClass::PierArea),
        "street_polygons" => (kind? == "pedestrian").then_some(DrawClass::PedestrianArea),
        "buildings" => Some(DrawClass::Building),
        "streets" => {
            let tunnel = layer
                .get(f, "tunnel")
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            match street_class(kind?)? {
                StreetClass::Road(kind) => Some(DrawClass::Street { kind, tunnel }),
                StreetClass::Rail => Some(DrawClass::Rail { tunnel }),
            }
        }
        "boundaries" => {
            if layer
                .get(f, "maritime")
                .and_then(|v| v.as_bool())
                .unwrap_or(false)
            {
                return None;
            }
            match layer.get(f, "admin_level").and_then(|v| v.as_i64())? {
                2 => Some(DrawClass::Boundary { country: true }),
                4 => Some(DrawClass::Boundary { country: false }),
                _ => None,
            }
        }
        _ => None,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::super::mvt::{GeomType, MvtTile, MvtValue, decode};
    use super::*;

    const FIXTURE: &[u8] = include_bytes!("testdata/osm-14-9520-6130.mvt");

    fn layer_with(name: &str, props: &[(&str, MvtValue)]) -> (MvtLayer, MvtFeature) {
        let mut layer = MvtLayer {
            name: name.to_owned(),
            extent: 4096,
            keys: Vec::new(),
            values: Vec::new(),
            features: Vec::new(),
        };
        let mut tags = Vec::new();
        for (i, (k, v)) in props.iter().enumerate() {
            layer.keys.push((*k).to_owned());
            layer.values.push(v.clone());
            tags.push(i as u32);
            tags.push(i as u32);
        }
        let feature = MvtFeature {
            id: None,
            tags,
            geom_type: GeomType::default(),
            geometry: Vec::new(),
        };
        (layer, feature)
    }

    fn s(v: &str) -> MvtValue {
        MvtValue::String(v.to_owned())
    }

    fn class_of(name: &str, props: &[(&str, MvtValue)]) -> Option<DrawClass> {
        let (l, f) = layer_with(name, props);
        classify(&l, &f)
    }

    fn fixture_classes(tile: &MvtTile, layer: &str) -> Vec<Option<DrawClass>> {
        let l = tile.layer(layer).expect("layer");
        l.features.iter().map(|f| classify(l, f)).collect()
    }

    #[test]
    fn fixture_buildings_are_buildings() {
        let t = decode(FIXTURE).unwrap();
        let c = fixture_classes(&t, "buildings");
        assert_eq!(c.len(), 124);
        assert!(c.iter().all(|c| *c == Some(DrawClass::Building)));
    }

    #[test]
    fn fixture_streets_match_their_kind() {
        let t = decode(FIXTURE).unwrap();
        let l = t.layer("streets").unwrap();
        for f in &l.features {
            let kind = l.str_of(f, "kind").unwrap();
            let expected = match kind {
                "residential" | "unclassified" | "living_street" => StreetKind::Minor,
                "service" => StreetKind::Service,
                "tertiary" | "tertiary_link" => StreetKind::Tertiary,
                "primary" | "secondary" | "trunk" => StreetKind::Arterial,
                "motorway" | "motorway_link" => StreetKind::Motorway,
                "pedestrian" => StreetKind::Pedestrian,
                "footway" | "steps" | "path" => StreetKind::Path,
                "cycleway" => StreetKind::Cycleway,
                "track" => StreetKind::Track,
                other => panic!("unexpected fixture street kind {other}"),
            };
            assert_eq!(
                classify(l, f),
                Some(DrawClass::Street {
                    kind: expected,
                    tunnel: false
                }),
                "kind {kind}"
            );
        }
    }

    #[test]
    fn fixture_labels_pois_peaks_are_not_drawn() {
        let t = decode(FIXTURE).unwrap();
        for name in ["street_labels", "pois", "mountain_peaks"] {
            let c = fixture_classes(&t, name);
            assert!(!c.is_empty(), "{name}");
            assert!(c.iter().all(Option::is_none), "{name}");
        }
    }

    #[test]
    fn fixture_land_and_water_lines() {
        let t = decode(FIXTURE).unwrap();
        let l = t.layer("land").unwrap();
        for f in &l.features {
            let kind = l.str_of(f, "kind").unwrap();
            assert_eq!(
                classify(l, f),
                land_kind(kind).map(DrawClass::Land),
                "land kind {kind}"
            );
        }
        let w = t.layer("water_lines").unwrap();
        for f in &w.features {
            let expected = match w.str_of(f, "kind") {
                Some("river" | "canal") => Some(DrawClass::Waterway { small: false }),
                Some("stream" | "ditch") => Some(DrawClass::Waterway { small: true }),
                _ => None,
            };
            assert_eq!(classify(w, f), expected);
        }
    }

    #[test]
    fn land_kinds() {
        let k = |kind: &str| class_of("land", &[("kind", s(kind))]);
        assert_eq!(k("forest"), Some(DrawClass::Land(LandKind::Forest)));
        assert_eq!(k("retail"), Some(DrawClass::Land(LandKind::Commercial)));
        assert_eq!(k("cemetery"), Some(DrawClass::Land(LandKind::Burial)));
        assert_eq!(k("swamp"), Some(DrawClass::Land(LandKind::Wetland)));
        assert_eq!(k("meadow"), Some(DrawClass::Land(LandKind::Grass)));
        assert_eq!(k("nonsense"), None);
    }

    #[test]
    fn water_and_other_polygon_layers() {
        assert_eq!(class_of("ocean", &[]), Some(DrawClass::Ocean));
        let w = |kind: &str| class_of("water_polygons", &[("kind", s(kind))]);
        assert_eq!(w("river"), Some(DrawClass::Water));
        assert_eq!(w("glacier"), Some(DrawClass::Glacier));
        assert_eq!(w("swimming"), None);
        assert_eq!(
            class_of("pier_polygons", &[("kind", s("pier"))]),
            Some(DrawClass::PierArea)
        );
        assert_eq!(
            class_of("street_polygons", &[("kind", s("pedestrian"))]),
            Some(DrawClass::PedestrianArea)
        );
        assert_eq!(class_of("street_polygons", &[("kind", s("service"))]), None);
    }

    #[test]
    fn link_streets_use_parent_kind_and_rails_are_rails() {
        let st = |kind: &str| class_of("streets", &[("kind", s(kind))]);
        let road = |kind| {
            Some(DrawClass::Street {
                kind,
                tunnel: false,
            })
        };
        assert_eq!(st("motorway_link"), road(StreetKind::Motorway));
        assert_eq!(st("primary_link"), road(StreetKind::Arterial));
        assert_eq!(st("busway"), road(StreetKind::Service));
        assert_eq!(st("rail"), Some(DrawClass::Rail { tunnel: false }));
        assert_eq!(st("tram"), Some(DrawClass::Rail { tunnel: false }));
        assert_eq!(st("bridleway"), None);
    }

    #[test]
    fn tunnel_flag() {
        let c = class_of(
            "streets",
            &[("kind", s("motorway")), ("tunnel", MvtValue::Bool(true))],
        );
        assert_eq!(
            c,
            Some(DrawClass::Street {
                kind: StreetKind::Motorway,
                tunnel: true
            })
        );
        let r = class_of(
            "streets",
            &[("kind", s("subway")), ("tunnel", MvtValue::Bool(true))],
        );
        assert_eq!(r, Some(DrawClass::Rail { tunnel: true }));
    }

    #[test]
    fn boundaries() {
        let b = |lvl: i64, maritime: Option<bool>| {
            let mut p = vec![("admin_level", MvtValue::Int(lvl))];
            if let Some(m) = maritime {
                p.push(("maritime", MvtValue::Bool(m)));
            }
            class_of("boundaries", &p)
        };
        assert_eq!(b(2, None), Some(DrawClass::Boundary { country: true }));
        assert_eq!(
            b(2, Some(false)),
            Some(DrawClass::Boundary { country: true })
        );
        assert_eq!(b(2, Some(true)), None);
        assert_eq!(b(4, None), Some(DrawClass::Boundary { country: false }));
        assert_eq!(b(6, None), None);
    }

    #[test]
    fn undrawn_layers() {
        for name in [
            "addresses",
            "place_labels",
            "public_transport",
            "aerialways",
            "ferries",
            "bridges",
            "sites",
            "boundary_labels",
            "water_polygons_labels",
        ] {
            assert_eq!(class_of(name, &[("kind", s("x"))]), None, "{name}");
        }
    }

    #[test]
    fn paint_order_and_shapes() {
        let land = DrawClass::Land(LandKind::Park);
        let st = DrawClass::Street {
            kind: StreetKind::Path,
            tunnel: false,
        };
        let bd = DrawClass::Boundary { country: false };
        assert!(DrawClass::Ocean < land);
        assert!(land < DrawClass::Building);
        assert!(DrawClass::Building < st);
        assert!(st < bd);
        assert!(DrawClass::Land(LandKind::Commercial) < DrawClass::Land(LandKind::Wetland));
        assert_eq!(DrawClass::Building.shape(), Shape::Fill);
        assert_eq!(st.shape(), Shape::Line);
        assert_eq!(DrawClass::Rail { tunnel: true }.shape(), Shape::Line);
    }

    #[test]
    fn min_zooms() {
        assert_eq!(DrawClass::Ocean.min_zoom(), 0);
        assert_eq!(DrawClass::Building.min_zoom(), 14);
        assert_eq!(DrawClass::Land(LandKind::Forest).min_zoom(), 7);
        assert_eq!(DrawClass::Boundary { country: false }.min_zoom(), 7);
        assert_eq!(
            DrawClass::Street {
                kind: StreetKind::Motorway,
                tunnel: false
            }
            .min_zoom(),
            5
        );
    }
}
