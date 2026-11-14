//! Frame statistics for the profiling hooks (AD-22).
//!
//! [`FrameStats`] is a fixed-size ring of frame times. Both buffers are
//! allocated once in [`FrameStats::with_capacity`]; `push` and `summary`
//! never allocate afterwards.

/// Default window: about 17 s of frames at 60 Hz, 8 s at 120 Hz.
pub const DEFAULT_CAPACITY: usize = 1024;

/// Frame budget in milliseconds for a refresh rate: 16.6 / 11.1 / 8.3 at
/// 60 / 90 / 120 Hz. Any other rate falls back to 60 Hz.
pub fn budget_ms_for_hz(hz: u32) -> f32 {
    match hz {
        90 => 11.1,
        120 => 8.3,
        _ => 16.6,
    }
}

/// Percentiles over the frames currently in the ring.
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct FrameSummary {
    /// Frames in the window.
    pub count: usize,
    pub p50: f32,
    pub p95: f32,
    pub p99: f32,
    pub max: f32,
}

/// Ring of the most recent frame times, in milliseconds.
#[derive(Clone, Debug)]
pub struct FrameStats {
    ring: Box<[f32]>,
    /// Scratch for sorting in `summary`, so it needs no allocation.
    sorted: Box<[f32]>,
    /// Next write position.
    head: usize,
    len: usize,
    total: u64,
}

impl Default for FrameStats {
    fn default() -> Self {
        Self::with_capacity(DEFAULT_CAPACITY)
    }
}

impl FrameStats {
    /// A ring holding the last `capacity` samples (at least 1).
    pub fn with_capacity(capacity: usize) -> Self {
        let capacity = capacity.max(1);
        Self {
            ring: vec![0.0; capacity].into_boxed_slice(),
            sorted: vec![0.0; capacity].into_boxed_slice(),
            head: 0,
            len: 0,
            total: 0,
        }
    }

    pub fn capacity(&self) -> usize {
        self.ring.len()
    }

    /// Samples currently held (at most the capacity).
    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Samples pushed since construction or the last [`clear`](Self::clear),
    /// including those that wrapped out of the ring.
    pub fn total_pushed(&self) -> u64 {
        self.total
    }

    /// Records one frame time. Non-finite or negative values are dropped.
    pub fn push(&mut self, ms: f32) {
        if !ms.is_finite() || ms < 0.0 {
            return;
        }
        self.ring[self.head] = ms;
        self.head += 1;
        if self.head == self.ring.len() {
            self.head = 0;
        }
        self.len = (self.len + 1).min(self.ring.len());
        self.total += 1;
    }

    pub fn clear(&mut self) {
        self.head = 0;
        self.len = 0;
        self.total = 0;
    }

    fn window(&self) -> &[f32] {
        // Until the ring wraps, valid samples are the first `len` slots;
        // afterwards every slot is valid and order does not matter.
        &self.ring[..self.len]
    }

    /// Frames in the window slower than `budget_ms`.
    pub fn over_budget(&self, budget_ms: f32) -> usize {
        self.window().iter().filter(|&&t| t > budget_ms).count()
    }

    /// Frames in the window slower than twice `budget_ms`.
    pub fn over_double_budget(&self, budget_ms: f32) -> usize {
        self.over_budget(budget_ms * 2.0)
    }

    /// p50/p95/p99/max by nearest rank. Sorts into the preallocated scratch
    /// buffer, so it takes `&mut self` but never allocates.
    pub fn summary(&mut self) -> FrameSummary {
        let n = self.len;
        if n == 0 {
            return FrameSummary::default();
        }
        self.sorted[..n].copy_from_slice(&self.ring[..n]);
        let sorted = &mut self.sorted[..n];
        sorted.sort_unstable_by(f32::total_cmp);
        let rank = |p: usize| sorted[(n * p).div_ceil(100).clamp(1, n) - 1];
        FrameSummary {
            count: n,
            p50: rank(50),
            p95: rank(95),
            p99: rank(99),
            max: sorted[n - 1],
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn budget_table() {
        assert_eq!(budget_ms_for_hz(60), 16.6);
        assert_eq!(budget_ms_for_hz(90), 11.1);
        assert_eq!(budget_ms_for_hz(120), 8.3);
    }

    #[test]
    fn unknown_rates_fall_back_to_60hz() {
        for hz in [0, 1, 30, 144, 165, u32::MAX] {
            assert_eq!(budget_ms_for_hz(hz), 16.6, "{hz}");
        }
    }

    #[test]
    fn empty_stats() {
        let mut s = FrameStats::with_capacity(8);
        assert!(s.is_empty());
        assert_eq!(s.summary(), FrameSummary::default());
        assert_eq!(s.over_budget(1.0), 0);
        assert_eq!(s.over_double_budget(1.0), 0);
    }

    #[test]
    fn known_distribution_percentiles() {
        // 1.0, 2.0 ... 100.0 ms.
        let mut s = FrameStats::with_capacity(100);
        for i in 1..=100 {
            s.push(i as f32);
        }
        let sum = s.summary();
        assert_eq!(sum.count, 100);
        assert_eq!(sum.p50, 50.0);
        assert_eq!(sum.p95, 95.0);
        assert_eq!(sum.p99, 99.0);
        assert_eq!(sum.max, 100.0);
    }

    #[test]
    fn insertion_order_does_not_matter() {
        let mut s = FrameStats::with_capacity(100);
        for i in (1..=100).rev() {
            s.push(i as f32);
        }
        let sum = s.summary();
        assert_eq!(
            (sum.p50, sum.p95, sum.p99, sum.max),
            (50.0, 95.0, 99.0, 100.0)
        );
    }

    #[test]
    fn single_sample() {
        let mut s = FrameStats::with_capacity(4);
        s.push(7.5);
        let sum = s.summary();
        assert_eq!(
            (sum.count, sum.p50, sum.p95, sum.p99, sum.max),
            (1, 7.5, 7.5, 7.5, 7.5)
        );
    }

    #[test]
    fn ten_thousand_samples_wrap_without_panic() {
        // Ring of 1000; the window must hold only the newest 1000 samples.
        let mut s = FrameStats::with_capacity(1000);
        for i in 0..10_000u32 {
            s.push((i % 1000) as f32 / 10.0); // 0.0 ..= 99.9, repeating
        }
        assert_eq!(s.len(), 1000);
        assert_eq!(s.total_pushed(), 10_000);
        let sum = s.summary();
        assert_eq!(sum.count, 1000);
        assert_eq!(sum.max, 99.9);
        assert_eq!(sum.p50, 49.9);
        assert_eq!(sum.p95, 94.9);
        assert_eq!(sum.p99, 98.9);
    }

    #[test]
    fn wrap_keeps_only_the_newest_samples() {
        let mut s = FrameStats::with_capacity(4);
        for ms in [100.0, 100.0, 100.0, 100.0, 1.0, 2.0, 3.0, 4.0] {
            s.push(ms);
        }
        let sum = s.summary();
        assert_eq!(sum.max, 4.0, "the 100 ms frames wrapped out");
        assert_eq!(s.over_budget(10.0), 0);
    }

    #[test]
    fn over_budget_counts_known_distribution() {
        // 10_000 frames: 9_850 at 8 ms, 100 at 20 ms (over, not double),
        // 50 at 40 ms (over double of 16.6 = 33.2).
        let mut s = FrameStats::with_capacity(10_000);
        for _ in 0..9_850 {
            s.push(8.0);
        }
        for _ in 0..100 {
            s.push(20.0);
        }
        for _ in 0..50 {
            s.push(40.0);
        }
        let budget = budget_ms_for_hz(60);
        assert_eq!(s.over_budget(budget), 150);
        assert_eq!(s.over_double_budget(budget), 50);
        let sum = s.summary();
        assert_eq!(sum.p50, 8.0);
        assert_eq!(sum.p99, 20.0); // rank 9900 of 10000 is the first 20.0
        assert_eq!(sum.max, 40.0);
    }

    #[test]
    fn exactly_on_budget_is_not_over() {
        let mut s = FrameStats::with_capacity(4);
        s.push(16.6);
        assert_eq!(s.over_budget(16.6), 0);
        assert_eq!(s.over_double_budget(8.3), 0, "exactly double is not over");
        assert_eq!(s.over_double_budget(8.0), 1);
    }

    #[test]
    fn invalid_samples_are_dropped() {
        let mut s = FrameStats::with_capacity(4);
        s.push(f32::NAN);
        s.push(f32::INFINITY);
        s.push(-1.0);
        assert!(s.is_empty());
        assert_eq!(s.total_pushed(), 0);
        s.push(0.0);
        assert_eq!(s.len(), 1);
    }

    #[test]
    fn clear_resets_everything() {
        let mut s = FrameStats::with_capacity(4);
        for i in 0..10 {
            s.push(i as f32);
        }
        s.clear();
        assert!(s.is_empty());
        assert_eq!(s.total_pushed(), 0);
        s.push(2.0);
        assert_eq!(s.summary().max, 2.0);
    }

    #[test]
    fn zero_capacity_is_bumped_to_one() {
        let mut s = FrameStats::with_capacity(0);
        assert_eq!(s.capacity(), 1);
        s.push(1.0);
        s.push(2.0);
        assert_eq!(s.summary().max, 2.0);
    }

    #[test]
    fn default_capacity() {
        assert_eq!(FrameStats::default().capacity(), DEFAULT_CAPACITY);
    }

    #[test]
    fn push_and_summary_do_not_reallocate() {
        let mut s = FrameStats::with_capacity(64);
        let ring_ptr = s.ring.as_ptr();
        let sorted_ptr = s.sorted.as_ptr();
        for i in 0..1000 {
            s.push(i as f32);
            if i % 100 == 0 {
                let _ = s.summary();
            }
        }
        assert_eq!(s.ring.as_ptr(), ring_ptr);
        assert_eq!(s.sorted.as_ptr(), sorted_ptr);
    }
}
