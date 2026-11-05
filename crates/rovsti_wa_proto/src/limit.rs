//! Rate limiting and retry pacing. Time is always passed in by the caller.

use std::collections::HashMap;
use std::net::IpAddr;

#[derive(Clone, Debug, PartialEq)]
pub struct TokenBucket {
    capacity: f64,
    refill_per_sec: f64,
    tokens: f64,
    last_ms: Option<u64>,
}

impl TokenBucket {
    /// A full bucket.
    pub fn new(capacity: u32, refill_per_sec: f64) -> Self {
        Self {
            capacity: f64::from(capacity),
            refill_per_sec,
            tokens: f64::from(capacity),
            last_ms: None,
        }
    }

    fn refill(&mut self, now_ms: u64) {
        if let Some(last) = self.last_ms {
            let dt = now_ms.saturating_sub(last) as f64 / 1000.0;
            self.tokens = (self.tokens + dt * self.refill_per_sec).min(self.capacity);
        }
        self.last_ms = Some(self.last_ms.map_or(now_ms, |l| l.max(now_ms)));
    }

    /// Takes one token if available.
    pub fn try_take(&mut self, now_ms: u64) -> bool {
        self.refill(now_ms);
        if self.tokens >= 1.0 {
            self.tokens -= 1.0;
            true
        } else {
            false
        }
    }

    /// True when the bucket has refilled completely (safe to forget).
    fn is_idle(&self, now_ms: u64) -> bool {
        let mut copy = self.clone();
        copy.refill(now_ms);
        copy.tokens >= copy.capacity
    }
}

/// One bucket per client address, used to slow down failed authentication.
#[derive(Clone, Debug)]
pub struct AuthLimiter {
    capacity: u32,
    refill_per_sec: f64,
    max_tracked: usize,
    buckets: HashMap<IpAddr, TokenBucket>,
}

impl AuthLimiter {
    pub fn new(capacity: u32, refill_per_sec: f64, max_tracked: usize) -> Self {
        Self {
            capacity,
            refill_per_sec,
            max_tracked: max_tracked.max(1),
            buckets: HashMap::new(),
        }
    }

    /// Records one failed attempt from `ip`. Returns false when `ip` is over
    /// the limit and should get `RateLimited`.
    pub fn allow(&mut self, ip: IpAddr, now_ms: u64) -> bool {
        if !self.buckets.contains_key(&ip) && self.buckets.len() >= self.max_tracked {
            self.prune(now_ms);
        }
        self.buckets
            .entry(ip)
            .or_insert_with(|| TokenBucket::new(self.capacity, self.refill_per_sec))
            .try_take(now_ms)
    }

    pub fn tracked(&self) -> usize {
        self.buckets.len()
    }

    fn prune(&mut self, now_ms: u64) {
        self.buckets.retain(|_, b| !b.is_idle(now_ms));
        while self.buckets.len() >= self.max_tracked {
            let oldest = self
                .buckets
                .iter()
                .min_by_key(|(_, b)| b.last_ms)
                .map(|(ip, _)| *ip);
            match oldest {
                Some(ip) => self.buckets.remove(&ip),
                None => break,
            };
        }
    }
}

/// Reconnect delay: 500 ms doubling per attempt, capped at 30 s. Callers add
/// jitter.
pub fn backoff_ms(attempt: u32) -> u64 {
    500u64.saturating_mul(1u64 << attempt.min(16)).min(30_000)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bucket_drains_and_refills() {
        let mut b = TokenBucket::new(3, 1.0);
        assert!(b.try_take(0));
        assert!(b.try_take(0));
        assert!(b.try_take(0));
        assert!(!b.try_take(0));
        assert!(!b.try_take(900));
        assert!(b.try_take(1_000));
        assert!(!b.try_take(1_000));
        // Refill is capped at capacity.
        assert!(b.try_take(100_000));
        assert!(b.try_take(100_000));
        assert!(b.try_take(100_000));
        assert!(!b.try_take(100_000));
    }

    #[test]
    fn bucket_ignores_clock_going_backwards() {
        let mut b = TokenBucket::new(1, 1.0);
        assert!(b.try_take(10_000));
        assert!(!b.try_take(5_000));
        assert!(b.try_take(11_000));
    }

    #[test]
    fn limiter_is_per_ip_and_bounded() {
        let a: IpAddr = "10.0.0.1".parse().unwrap();
        let b: IpAddr = "10.0.0.2".parse().unwrap();
        let mut l = AuthLimiter::new(2, 0.1, 2);
        assert!(l.allow(a, 0));
        assert!(l.allow(a, 0));
        assert!(!l.allow(a, 0));
        assert!(l.allow(b, 0));
        let c: IpAddr = "10.0.0.3".parse().unwrap();
        assert!(l.allow(c, 1));
        assert!(l.tracked() <= 2);
        // After a long pause everything has refilled and gets pruned.
        let d: IpAddr = "10.0.0.4".parse().unwrap();
        assert!(l.allow(d, 1_000_000));
        assert!(l.tracked() <= 2);
    }

    #[test]
    fn backoff_doubles_and_caps() {
        let seq: Vec<u64> = (0..8).map(backoff_ms).collect();
        assert_eq!(
            seq,
            [500, 1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000]
        );
        assert_eq!(backoff_ms(u32::MAX), 30_000);
    }
}
