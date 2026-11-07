//! Progress of an offline map area download. Pure bookkeeping, no I/O.

use std::collections::BTreeSet;

use super::slippy::TileId;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AreaDownload {
    pub total: usize,
    pub done: usize,
    pub failed: usize,
    pub pending: BTreeSet<TileId>,
    pub cancelled: bool,
}

impl AreaDownload {
    pub fn new(ids: impl IntoIterator<Item = TileId>) -> Self {
        let pending: BTreeSet<TileId> = ids.into_iter().collect();
        Self {
            total: pending.len(),
            done: 0,
            failed: 0,
            pending,
            cancelled: false,
        }
    }

    /// Records a finished tile. `false` (and no change) for ids that are not
    /// part of this download or were already recorded.
    pub fn record(&mut self, id: TileId, ok: bool) -> bool {
        if !self.pending.remove(&id) {
            return false;
        }
        if ok {
            self.done += 1;
        } else {
            self.failed += 1;
        }
        true
    }

    /// Stops the download; tiles already saved are kept.
    pub fn cancel(&mut self) {
        self.cancelled = true;
        self.pending.clear();
    }

    pub fn is_finished(&self) -> bool {
        self.cancelled || self.pending.is_empty()
    }

    /// Share of tiles handled (saved or failed), `0.0..=1.0`; `1.0` when empty.
    pub fn fraction(&self) -> f32 {
        if self.total == 0 {
            return 1.0;
        }
        (((self.done + self.failed) as f32) / self.total as f32).clamp(0.0, 1.0)
    }

    pub fn progress_text(&self) -> String {
        format!(
            "Downloading {} of {} tiles",
            self.done + self.failed,
            self.total
        )
    }

    pub fn finished_text(&self) -> String {
        if self.cancelled {
            format!(
                "Download cancelled. {} of {} tiles kept.",
                self.done, self.total
            )
        } else {
            format!(
                "Map area ready offline. {} tiles, {} failed.",
                self.done, self.failed
            )
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    fn t(x: u32) -> TileId {
        TileId::new(10, x, 0).unwrap()
    }

    #[test]
    fn counts_and_ignores_foreign_and_repeats() {
        let mut d = AreaDownload::new((0..4).map(t));
        assert_eq!(d.total, 4);
        assert!(!d.is_finished());
        assert!(d.record(t(0), true));
        assert!(!d.record(t(0), true));
        assert!(!d.record(t(99), true));
        assert!(d.record(t(1), false));
        assert_eq!((d.done, d.failed, d.pending.len()), (1, 1, 2));
        assert!((d.fraction() - 0.5).abs() < 1e-6);
        assert_eq!(d.progress_text(), "Downloading 2 of 4 tiles");
        d.record(t(2), true);
        d.record(t(3), true);
        assert!(d.is_finished());
        assert_eq!(
            d.finished_text(),
            "Map area ready offline. 3 tiles, 1 failed."
        );
    }

    #[test]
    fn duplicates_in_input_count_once() {
        let d = AreaDownload::new([t(1), t(1), t(2)]);
        assert_eq!(d.total, 2);
    }

    #[test]
    fn cancel_and_empty() {
        let mut d = AreaDownload::new((0..900).map(|x| TileId::new(14, x, 0).unwrap()));
        for x in 0..120 {
            d.record(TileId::new(14, x, 0).unwrap(), true);
        }
        d.cancel();
        assert!(d.is_finished());
        assert!(!d.record(TileId::new(14, 500, 0).unwrap(), true));
        assert_eq!(
            d.finished_text(),
            "Download cancelled. 120 of 900 tiles kept."
        );
        let e = AreaDownload::new([]);
        assert!(e.is_finished());
        assert_eq!(e.fraction(), 1.0);
    }
}
