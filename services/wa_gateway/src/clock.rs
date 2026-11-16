//! Time for the parts of the gateway that pace themselves against WhatsApp (the contact allowances, the
//! cooldown and the people job): the wall clock in production, a hand-driven one in tests, so a test can check
//! that nothing is sent before a wait ends without waiting for real.

use futures::future::BoxFuture;

/// Wall time in unix ms, and a sleep that belongs to the same clock.
pub trait Clock: Send + Sync + 'static {
    fn now_ms(&self) -> i64;
    fn sleep(&self, ms: u64) -> BoxFuture<'static, ()>;
}

/// The system clock and tokio's timer.
#[derive(Clone, Copy, Debug, Default)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now_ms(&self) -> i64 {
        crate::now_ms()
    }

    fn sleep(&self, ms: u64) -> BoxFuture<'static, ()> {
        Box::pin(tokio::time::sleep(std::time::Duration::from_millis(ms)))
    }
}

#[cfg(any(test, feature = "fake"))]
pub use manual::ManualClock;

#[cfg(any(test, feature = "fake"))]
mod manual {
    use std::sync::Arc;

    use futures::future::BoxFuture;
    use tokio::sync::watch;

    use super::Clock;

    /// A clock for tests. `auto`: every sleep moves the clock to its end at once (a job runs at full speed, its
    /// waits still counted). Otherwise sleeps end only when the test calls [`ManualClock::advance`].
    #[derive(Clone, Debug)]
    pub struct ManualClock {
        now: Arc<watch::Sender<i64>>,
        auto: bool,
    }

    impl ManualClock {
        pub fn new(start_ms: i64, auto: bool) -> Self {
            Self {
                now: Arc::new(watch::Sender::new(start_ms)),
                auto,
            }
        }

        /// Moves the clock forward; the sleeps that end by then wake.
        pub fn advance(&self, ms: u64) {
            let ms = i64::try_from(ms).unwrap_or(i64::MAX);
            self.now.send_modify(|now| *now = now.saturating_add(ms));
        }

        /// Sleeps that are waiting right now.
        pub fn sleepers(&self) -> usize {
            self.now.receiver_count()
        }
    }

    impl Clock for ManualClock {
        fn now_ms(&self) -> i64 {
            *self.now.borrow()
        }

        fn sleep(&self, ms: u64) -> BoxFuture<'static, ()> {
            let ms = i64::try_from(ms).unwrap_or(i64::MAX);
            let until = self.now_ms().saturating_add(ms);
            if self.auto {
                self.now.send_modify(|now| *now = (*now).max(until));
                return Box::pin(tokio::task::yield_now());
            }
            let mut rx = self.now.subscribe();
            Box::pin(async move {
                while *rx.borrow_and_update() < until {
                    if rx.changed().await.is_err() {
                        return;
                    }
                }
            })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn a_manual_sleep_ends_when_the_clock_passes_it() {
        let clock = ManualClock::new(1_000, false);
        let sleep = clock.sleep(500);
        let task = tokio::spawn(sleep);
        tokio::task::yield_now().await;
        clock.advance(499);
        tokio::task::yield_now().await;
        assert!(!task.is_finished());
        clock.advance(1);
        task.await.unwrap();
        assert_eq!(clock.now_ms(), 1_500);
        let auto = ManualClock::new(0, true);
        auto.sleep(60_000).await;
        assert_eq!(auto.now_ms(), 60_000);
    }
}
