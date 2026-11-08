//! The small value types of picking a trip's two ends, shared by the pick state
//! machine and the apps: [`Destination`], [`Slot`], [`Ends`] and the text
//! helpers that describe them.

use rovsti_geo::{Coord, Diameter, DiameterError};

/// Where a trip is headed.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Destination {
    pub label: String,
    pub coord: Coord,
    /// The transit stop this is, when it came from an imported feed.
    #[serde(default)]
    pub gtfs_stop: Option<String>,
    /// The line it was chosen on in the stop sheet, drawn on the map.
    #[serde(default)]
    pub pattern: Option<u32>,
}

impl Destination {
    pub fn new(label: impl Into<String>, coord: Coord) -> Self {
        Self {
            label: label.into(),
            coord,
            gtfs_stop: None,
            pattern: None,
        }
    }

    /// A raw point dropped on the map: it has no name.
    pub fn dropped(coord: Coord) -> Self {
        Self::new(String::new(), coord)
    }

    /// Whether the point has a name worth showing.
    pub fn is_named(&self) -> bool {
        !self.label.trim().is_empty()
    }

    /// The confirmation sheet's heading.
    pub fn title(&self) -> String {
        if self.is_named() {
            format!("Use {}", self.label)
        } else {
            "Use this point".to_owned()
        }
    }
}

/// Which end of the diameter a point goes to.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Slot {
    #[default]
    A,
    B,
}

impl Slot {
    pub fn label(self) -> &'static str {
        match self {
            Self::A => "A",
            Self::B => "B",
        }
    }

    /// The picker sheet's heading while this end is being chosen.
    pub fn picker_title(self) -> &'static str {
        match self {
            Self::A => "Choose A",
            Self::B => "Choose B",
        }
    }
}

/// The two points chosen so far.
#[derive(Clone, Debug, Default, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Ends {
    pub a: Option<Destination>,
    pub b: Option<Destination>,
}

impl Ends {
    pub fn get(&self, slot: Slot) -> Option<&Destination> {
        match slot {
            Slot::A => self.a.as_ref(),
            Slot::B => self.b.as_ref(),
        }
    }

    pub fn set(&mut self, slot: Slot, dest: Destination) {
        match slot {
            Slot::A => self.a = Some(dest),
            Slot::B => self.b = Some(dest),
        }
    }

    pub fn is_empty(&self) -> bool {
        self.a.is_none() && self.b.is_none()
    }

    /// Where the next picked point goes: A until it is set, then B.
    pub fn next_slot(&self) -> Slot {
        if self.a.is_none() { Slot::A } else { Slot::B }
    }

    /// Fills both ends at once (a recent trip).
    pub fn set_pair(&mut self, a: Destination, b: Destination) {
        self.a = Some(a);
        self.b = Some(b);
    }

    /// Exchanges A and B (the left and right sides trade places).
    pub fn swap(&mut self) {
        std::mem::swap(&mut self.a, &mut self.b);
    }

    /// The circle on AB. `None` until both points are set; `Some(Err)` when
    /// they cannot form a diameter.
    pub fn diameter(&self) -> Option<Result<Diameter, DiameterError>> {
        let (a, b) = (self.a.as_ref()?, self.b.as_ref()?);
        Some(Diameter::new(a.coord, b.coord))
    }
}

/// `41.0370, 28.9850`: for a details line only, never a name or a main-flow label.
pub fn coord_text(coord: Coord) -> String {
    format!("{:.4}, {:.4}", coord.lat, coord.lon)
}

/// "450 m", "1.2 km": metres to the nearest 10 below a kilometre.
pub fn distance_text(metres: f64) -> String {
    // Rounded first, so 995 m reads "1.0 km", never "1000 m".
    let tens = (metres.max(0.0) / 10.0).round() as i64 * 10;
    if tens < 1_000 {
        format!("{tens} m")
    } else {
        format!("{:.1} km", metres / 1_000.0)
    }
}
