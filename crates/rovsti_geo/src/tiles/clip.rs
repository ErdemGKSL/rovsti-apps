//! Polygon and polyline clipping and Douglas-Peucker simplification (pure, f32).

/// Axis-aligned rectangle.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Rect {
    pub min: (f32, f32),
    pub max: (f32, f32),
}

impl Rect {
    pub fn new(min: (f32, f32), max: (f32, f32)) -> Self {
        Self { min, max }
    }

    pub fn contains(&self, p: (f32, f32)) -> bool {
        p.0 >= self.min.0 && p.0 <= self.max.0 && p.1 >= self.min.1 && p.1 <= self.max.1
    }

    pub fn intersects(&self, o: &Rect) -> bool {
        self.min.0 <= o.max.0
            && o.min.0 <= self.max.0
            && self.min.1 <= o.max.1
            && o.min.1 <= self.max.1
    }
}

#[derive(Clone, Copy)]
enum Edge {
    Left,
    Right,
    Top,
    Bottom,
}

fn inside(p: (f32, f32), e: Edge, r: &Rect) -> bool {
    match e {
        Edge::Left => p.0 >= r.min.0,
        Edge::Right => p.0 <= r.max.0,
        Edge::Top => p.1 >= r.min.1,
        Edge::Bottom => p.1 <= r.max.1,
    }
}

fn cross(a: (f32, f32), b: (f32, f32), e: Edge, r: &Rect) -> (f32, f32) {
    let lerp = |t: f32| (a.0 + (b.0 - a.0) * t, a.1 + (b.1 - a.1) * t);
    match e {
        Edge::Left | Edge::Right => {
            let x = if matches!(e, Edge::Left) {
                r.min.0
            } else {
                r.max.0
            };
            let t = (x - a.0) / (b.0 - a.0);
            (x, lerp(t).1)
        }
        Edge::Top | Edge::Bottom => {
            let y = if matches!(e, Edge::Top) {
                r.min.1
            } else {
                r.max.1
            };
            let t = (y - a.1) / (b.1 - a.1);
            (lerp(t).0, y)
        }
    }
}

/// Sutherland-Hodgman. `ring` is open (the closing point is not repeated).
/// Returns an empty vec when nothing is left.
pub fn clip_polygon(ring: &[(f32, f32)], r: &Rect) -> Vec<(f32, f32)> {
    let mut out: Vec<(f32, f32)> = ring.to_vec();
    for e in [Edge::Left, Edge::Right, Edge::Top, Edge::Bottom] {
        if out.is_empty() {
            break;
        }
        let input = std::mem::take(&mut out);
        let mut prev = input[input.len() - 1];
        for &cur in &input {
            let (cin, pin) = (inside(cur, e, r), inside(prev, e, r));
            if cin {
                if !pin {
                    out.push(cross(prev, cur, e, r));
                }
                out.push(cur);
            } else if pin {
                out.push(cross(prev, cur, e, r));
            }
            prev = cur;
        }
    }
    if out.len() < 3 {
        out.clear();
    }
    out
}

/// Liang-Barsky on each segment; the line is split where it leaves the rect.
pub fn clip_polyline(line: &[(f32, f32)], r: &Rect) -> Vec<Vec<(f32, f32)>> {
    let mut parts: Vec<Vec<(f32, f32)>> = Vec::new();
    let mut cur: Vec<(f32, f32)> = Vec::new();
    let finish = |cur: &mut Vec<(f32, f32)>, parts: &mut Vec<Vec<(f32, f32)>>| {
        if cur.len() >= 2 {
            parts.push(std::mem::take(cur));
        } else {
            cur.clear();
        }
    };
    for w in line.windows(2) {
        let (a, b) = (w[0], w[1]);
        let (dx, dy) = (b.0 - a.0, b.1 - a.1);
        let (mut t0, mut t1) = (0.0f32, 1.0f32);
        let checks = [
            (-dx, a.0 - r.min.0),
            (dx, r.max.0 - a.0),
            (-dy, a.1 - r.min.1),
            (dy, r.max.1 - a.1),
        ];
        let mut visible = true;
        for (p, q) in checks {
            if p == 0.0 {
                if q < 0.0 {
                    visible = false;
                    break;
                }
            } else {
                let t = q / p;
                if p < 0.0 {
                    t0 = t0.max(t);
                } else {
                    t1 = t1.min(t);
                }
            }
        }
        if !visible || t0 > t1 {
            finish(&mut cur, &mut parts);
            continue;
        }
        if t0 > 0.0 {
            finish(&mut cur, &mut parts);
        }
        if cur.is_empty() {
            cur.push((a.0 + dx * t0, a.1 + dy * t0));
        }
        cur.push(if t1 < 1.0 {
            (a.0 + dx * t1, a.1 + dy * t1)
        } else {
            b
        });
        if t1 < 1.0 {
            finish(&mut cur, &mut parts);
        }
    }
    finish(&mut cur, &mut parts);
    parts
}

fn dist_sq_to_segment(p: (f32, f32), a: (f32, f32), b: (f32, f32)) -> f32 {
    let (dx, dy) = (b.0 - a.0, b.1 - a.1);
    let len2 = dx * dx + dy * dy;
    let t = if len2 == 0.0 {
        0.0
    } else {
        (((p.0 - a.0) * dx + (p.1 - a.1) * dy) / len2).clamp(0.0, 1.0)
    };
    let (cx, cy) = (a.0 + dx * t, a.1 + dy * t);
    (p.0 - cx).powi(2) + (p.1 - cy).powi(2)
}

/// Iterative Douglas-Peucker keeping both ends. With `closed`, `points` is an
/// open ring (closing point not repeated) and its first point is kept.
pub fn simplify(points: &[(f32, f32)], tolerance: f32, closed: bool) -> Vec<(f32, f32)> {
    if points.len() < 3 || tolerance <= 0.0 {
        return points.to_vec();
    }
    let mut pts = points.to_vec();
    if closed {
        pts.push(points[0]);
    }
    let n = pts.len();
    let mut keep = vec![false; n];
    keep[0] = true;
    keep[n - 1] = true;
    let tol2 = tolerance * tolerance;
    let mut stack = vec![(0usize, n - 1)];
    while let Some((s, e)) = stack.pop() {
        if e <= s + 1 {
            continue;
        }
        let (mut best, mut idx) = (0.0f32, s);
        for (i, &p) in pts.iter().enumerate().take(e).skip(s + 1) {
            let d = dist_sq_to_segment(p, pts[s], pts[e]);
            if d > best {
                best = d;
                idx = i;
            }
        }
        if best > tol2 {
            keep[idx] = true;
            stack.push((s, idx));
            stack.push((idx, e));
        }
    }
    let mut out: Vec<(f32, f32)> = pts
        .iter()
        .zip(&keep)
        .filter_map(|(p, &k)| k.then_some(*p))
        .collect();
    if closed {
        out.pop();
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const R: Rect = Rect {
        min: (0.0, 0.0),
        max: (10.0, 10.0),
    };

    #[test]
    fn square_half_outside() {
        let sq = [(5.0, 2.0), (15.0, 2.0), (15.0, 8.0), (5.0, 8.0)];
        let out = clip_polygon(&sq, &R);
        assert_eq!(out.len(), 4);
        for p in &out {
            assert!(R.contains(*p));
        }
        let max_x = out.iter().map(|p| p.0).fold(f32::MIN, f32::max);
        assert_eq!(max_x, 10.0);
        let min_x = out.iter().map(|p| p.0).fold(f32::MAX, f32::min);
        assert_eq!(min_x, 5.0);
    }

    #[test]
    fn polygon_inside_and_outside() {
        let inner = [(1.0, 1.0), (2.0, 1.0), (2.0, 2.0)];
        assert_eq!(clip_polygon(&inner, &R).len(), 3);
        let outer = [(20.0, 20.0), (30.0, 20.0), (30.0, 30.0)];
        assert!(clip_polygon(&outer, &R).is_empty());
    }

    #[test]
    fn polygon_covering_rect_gives_rect() {
        let big = [(-5.0, -5.0), (15.0, -5.0), (15.0, 15.0), (-5.0, 15.0)];
        let out = clip_polygon(&big, &R);
        assert_eq!(out.len(), 4);
        for p in &out {
            assert!(p.0 == 0.0 || p.0 == 10.0);
            assert!(p.1 == 0.0 || p.1 == 10.0);
        }
    }

    #[test]
    fn line_crossing_twice_gives_two_parts() {
        // Goes in, out through the right, around, and in again.
        let line = [
            (-5.0, 5.0),
            (5.0, 5.0),
            (15.0, 5.0),
            (15.0, 8.0),
            (5.0, 8.0),
        ];
        let parts = clip_polyline(&line, &R);
        assert_eq!(parts.len(), 2);
        assert_eq!(parts[0].first(), Some(&(0.0, 5.0)));
        assert_eq!(parts[0].last(), Some(&(10.0, 5.0)));
        assert_eq!(parts[1].first(), Some(&(10.0, 8.0)));
        assert_eq!(parts[1].last(), Some(&(5.0, 8.0)));
    }

    #[test]
    fn line_inside_and_outside() {
        let inside = [(1.0, 1.0), (2.0, 2.0), (3.0, 1.0)];
        assert_eq!(clip_polyline(&inside, &R), vec![inside.to_vec()]);
        let outside = [(20.0, 1.0), (30.0, 2.0)];
        assert!(clip_polyline(&outside, &R).is_empty());
        let parallel = [(-5.0, 20.0), (15.0, 20.0)];
        assert!(clip_polyline(&parallel, &R).is_empty());
    }

    #[test]
    fn simplify_straight_line_keeps_ends() {
        let line: Vec<_> = (0..10).map(|i| (i as f32, 0.0)).collect();
        assert_eq!(simplify(&line, 0.1, false), vec![(0.0, 0.0), (9.0, 0.0)]);
    }

    #[test]
    fn simplify_zigzag() {
        let zig = [(0.0, 0.0), (1.0, 2.0), (2.0, 0.0), (3.0, 2.0), (4.0, 0.0)];
        assert_eq!(simplify(&zig, 0.5, false), zig.to_vec());
        assert_eq!(simplify(&zig, 5.0, false).len(), 2);
    }

    #[test]
    fn simplify_closed_keeps_first() {
        let ring = [
            (0.0, 0.0),
            (5.0, 0.01),
            (10.0, 0.0),
            (10.0, 10.0),
            (0.0, 10.0),
        ];
        let out = simplify(&ring, 0.1, true);
        assert_eq!(out[0], (0.0, 0.0));
        assert_eq!(out.len(), 4);
    }
}
