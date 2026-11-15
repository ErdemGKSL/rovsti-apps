//! Text fitting: every line of a card stays inside the glass panel.
//!
//! A text block is measured with `takumi::measure` at its real width, from the largest size down to a floor. The first
//! size whose lines all fit the width and that needs no more than `max_lines` lines wins (words do not break while a
//! smaller size can still help). When even the floor does not fit (a very long name, a long Turkish greeting), the
//! floor is used and the text is cut, measured, to the longest start that fits with "…" after it. Words longer than
//! the width break anywhere (`overflow-wrap: anywhere`) at the floor.
//!
//! The ellipsis is added here and not with CSS `line-clamp`: Takumi 2.18.2 places the clamp's "…" after the last
//! line without taking its width off the line, so it can cross the panel edge.

use takumi::prelude::{MeasuredNode, MeasuredTextRun, Node, RenderOptions, StyleSheet, Viewport};

use super::{CardError, fonts};

/// How one text block is set.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TextRule {
    /// Largest and smallest font size in pixels.
    pub max_px: f32,
    pub floor_px: f32,
    pub max_lines: u32,
    pub weight: u16,
    pub line_height: f32,
    /// Whether the block may wrap at all (a phone number stays on one line and shrinks).
    pub wrap: bool,
}

/// A block as it is set: the text (cut with "…" when [`Self::clamped`]) and its size.
#[derive(Clone, Debug, PartialEq)]
pub struct Fitted {
    pub text: String,
    pub size_px: f32,
    pub lines: u32,
    pub clamped: bool,
}

impl Fitted {
    /// The CSS of a block set at this size (`width` is the panel's inner width).
    pub fn css(&self, rule: &TextRule, width: f32) -> String {
        format!(
            "width: {width}px; max-width: {width}px; font-family: {family}; font-weight: {weight}; \
             font-size: {size}px; line-height: {lh}; text-align: center; {wrap}",
            family = fonts::FAMILY,
            weight = rule.weight,
            size = self.size_px,
            lh = rule.line_height,
            wrap = wrap_css(rule, self.clamped),
        )
    }
}

/// `white-space` and `overflow-wrap` of a block; words break anywhere only once the floor is reached.
fn wrap_css(rule: &TextRule, anywhere: bool) -> &'static str {
    match (rule.wrap, anywhere) {
        (false, _) => "white-space: nowrap;",
        (true, false) => "white-space: normal;",
        (true, true) => "white-space: normal; overflow-wrap: anywhere;",
    }
}

/// Step between two tried sizes.
const STEP_PX: f32 = 2.0;
/// Slack for sub-pixel advance rounding.
const EPSILON_PX: f32 = 0.5;
const ELLIPSIS: char = '…';

/// The lines of `text` set at `size_px` in a `width`-wide block: `(line count, widest line)`. `anywhere` lets words
/// break inside.
pub fn measure_lines(
    text: &str,
    width: f32,
    size_px: f32,
    rule: &TextRule,
    anywhere: bool,
) -> Result<(u32, f32), CardError> {
    let css = format!(
        ".m {{ width: {width}px; font-family: {family}; font-weight: {weight}; font-size: {size_px}px; \
           line-height: {lh}; {wrap} }}",
        family = fonts::FAMILY,
        weight = rule.weight,
        lh = rule.line_height,
        wrap = wrap_css(rule, anywhere),
    );
    let sheet = StyleSheet::parse(&css).map_err(|e| CardError::Style(e.to_string()))?;
    let node = Node::container([Node::text(text)]).with_class_name("m");
    let viewport_w = (width.ceil() as u32).saturating_add(4096);
    let options = RenderOptions::builder()
        .viewport(Viewport::new((viewport_w, 4096)))
        .node(node)
        .fonts(fonts::bundled()?)
        .stylesheet(sheet.into())
        .build();
    let measured = takumi::measure(options).map_err(|e| CardError::Render(e.to_string()))?;
    let mut runs = Vec::new();
    collect_runs(&measured, &mut runs);
    // Runs on one line share their top; a line can hold several runs (one per shaping segment).
    let mut lines: Vec<(f32, f32, f32)> = Vec::new();
    for run in &runs {
        match lines
            .iter_mut()
            .find(|(top, _, _)| (top - run.y).abs() < size_px * 0.5)
        {
            Some(line) => {
                line.1 = line.1.min(run.x);
                line.2 = line.2.max(run.x + run.width);
            }
            None => lines.push((run.y, run.x, run.x + run.width)),
        }
    }
    let widest = lines
        .iter()
        .map(|(_, left, right)| right - left)
        .fold(0.0, f32::max);
    Ok((lines.len() as u32, widest))
}

fn collect_runs(node: &MeasuredNode, out: &mut Vec<MeasuredTextRun>) {
    out.extend(
        node.runs
            .iter()
            .filter(|r| !r.text.trim().is_empty())
            .cloned(),
    );
    for child in &node.children {
        collect_runs(child, out);
    }
}

fn fits(lines: u32, widest: f32, width: f32, rule: &TextRule) -> bool {
    lines <= rule.max_lines && widest <= width + EPSILON_PX
}

/// Sets `text` in a `width`-wide block under `rule`.
pub fn fit(text: &str, width: f32, rule: &TextRule) -> Result<Fitted, CardError> {
    let mut size = rule.max_px;
    while size >= rule.floor_px {
        let (lines, widest) = measure_lines(text, width, size, rule, false)?;
        if fits(lines, widest, width, rule) {
            return Ok(Fitted {
                text: text.to_owned(),
                size_px: size,
                lines: lines.max(1),
                clamped: false,
            });
        }
        size -= STEP_PX;
    }
    // The floor, with words allowed to break.
    let floor = rule.floor_px;
    let (lines, widest) = measure_lines(text, width, floor, rule, true)?;
    if fits(lines, widest, width, rule) {
        return Ok(Fitted {
            text: text.to_owned(),
            size_px: floor,
            lines: lines.max(1),
            clamped: true,
        });
    }
    // The longest start of the text that fits with an ellipsis (binary search over characters).
    let chars: Vec<char> = text.chars().collect();
    let cut = |n: usize| -> String {
        let mut s: String = chars[..n].iter().collect();
        s.truncate(s.trim_end_matches([' ', ',', '.', '-']).len());
        s.push(ELLIPSIS);
        s
    };
    let (mut lo, mut hi) = (0usize, chars.len());
    while lo < hi {
        let mid = (lo + hi).div_ceil(2);
        let (lines, widest) = measure_lines(&cut(mid), width, floor, rule, true)?;
        if fits(lines, widest, width, rule) {
            lo = mid;
        } else {
            hi = mid - 1;
        }
    }
    Ok(Fitted {
        text: cut(lo),
        size_px: floor,
        lines: rule.max_lines,
        clamped: true,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const RULE: TextRule = TextRule {
        max_px: 60.0,
        floor_px: 34.0,
        max_lines: 3,
        weight: 700,
        line_height: 1.18,
        wrap: true,
    };

    #[test]
    fn a_short_line_keeps_the_largest_size() {
        let f = fit("Ayşe", 544.0, &RULE).unwrap();
        assert_eq!((f.size_px, f.lines, f.clamped), (60.0, 1, false));
    }

    #[test]
    fn a_long_greeting_wraps_then_shrinks() {
        let f = fit(
            "Doğum günün kutlu olsun, Ayşe Yılmaz Karadenizoğlu Çağlayangil!",
            544.0,
            &RULE,
        )
        .unwrap();
        assert!(!f.clamped);
        assert!((2..=3).contains(&f.lines), "{f:?}");
        assert!(f.size_px < 60.0 && f.size_px >= 34.0, "{f:?}");
    }

    #[test]
    fn an_endless_text_is_cut_with_an_ellipsis_that_fits() {
        let long = "Wolfeschlegelsteinhausenbergerdorff ".repeat(12);
        let f = fit(&long, 544.0, &RULE).unwrap();
        assert!(f.clamped && f.text.ends_with(ELLIPSIS), "{f:?}");
        let (lines, widest) = measure_lines(&f.text, 544.0, f.size_px, &RULE, true).unwrap();
        assert!(lines <= 3 && widest <= 544.5, "{lines} {widest}");
    }

    #[test]
    fn a_number_that_cannot_wrap_shrinks_then_is_cut() {
        let rule = TextRule {
            max_lines: 1,
            wrap: false,
            ..RULE
        };
        let f = fit("+90 555 000 12 34", 544.0, &rule).unwrap();
        assert!(!f.clamped);
        let f = fit(&"+90 555 000 12 34 ".repeat(4), 544.0, &rule).unwrap();
        assert!(f.clamped && f.text.ends_with(ELLIPSIS));
    }
}
