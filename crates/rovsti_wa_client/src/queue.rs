//! The queue between the audio socket and the app, one per direction.
//!
//! It holds PCM frames and is measured in time, not in slots, so 20 ms and
//! 60 ms frames count alike. It never blocks the side that fills it and it
//! never keeps late audio; what goes is always the **oldest** audio:
//!
//! - More than `high` waiting (the network stalled and then delivered
//!   everything at once): cut down to `target` right away.
//! - A standing queue: when for a whole [`WINDOW_MS`] every new frame found
//!   more than `target` already waiting, the reader keeps pace but never
//!   catches up, so that surplus is delay and nothing else. It is cut. This
//!   is what brings the queue back after a burst that ended below `high`,
//!   and what keeps a clock that runs a little fast on the sending side from
//!   building up a delay.
//!
//! The reader takes frames without blocking ([`FrameQueue::try_pop`]), with a
//! bounded wait from a plain thread ([`FrameQueue::pop_timeout`]) or as a
//! [`Stream`] from async code.

use std::collections::VecDeque;
use std::pin::Pin;
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::task::{Context, Poll, Waker};
use std::time::{Duration, Instant};

use futures::Stream;
use rovsti_wa_proto::audio::samples_ms;

/// A queue is checked for a standing surplus once per this much audio put in.
pub const WINDOW_MS: u64 = 1_000;

/// How much a queue may hold and what a cut leaves, in milliseconds of audio.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Limits {
    /// More than this waiting: cut down to `target_ms` at once.
    pub high_ms: u64,
    /// What a cut leaves, and the most that may stand in the queue for a
    /// whole window. At least one frame always stays.
    pub target_ms: u64,
}

/// The peer's voice on its way to the player. The player takes a frame as
/// soon as the audio track has room, so anything waiting here is delay.
pub const SPEAKER_LIMITS: Limits = Limits {
    high_ms: 120,
    target_ms: 40,
};

/// The microphone on its way to the socket: 60 ms frames.
pub const MIC_LIMITS: Limits = Limits {
    high_ms: 240,
    target_ms: 60,
};

/// Why no frame came.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RecvError {
    /// Nothing is waiting (yet).
    Empty,
    /// The queue is closed and empty: the audio is over.
    Closed,
}

/// The other side is gone: the audio is over.
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
#[error("the call audio is closed")]
pub struct Closed;

/// What a queue saw since the last [`FrameQueue::take_stats`].
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct QueueStats {
    /// Frames put in.
    pub frames: u64,
    /// Mean of what was already waiting when a frame came, in milliseconds.
    pub mean_ms: u64,
    /// The most that was waiting when a frame came, in milliseconds.
    pub max_ms: u64,
    /// Milliseconds of the oldest audio cut to get back to the target.
    pub dropped_ms: u64,
    /// What is waiting right now, in milliseconds.
    pub now_ms: u64,
}

#[derive(Debug, Default)]
struct Inner {
    frames: VecDeque<Vec<i16>>,
    samples: usize,
    closed: bool,
    waker: Option<Waker>,
    pushed: u64,
    waiting_sum: u64,
    waiting_max: usize,
    dropped: u64,
    /// Samples put in since the standing check last ran.
    window: usize,
    /// The least that waited when a frame came, in this window.
    window_min: Option<usize>,
}

impl Inner {
    /// Drops the oldest frames while `keep_going` says so; one always stays.
    fn cut(&mut self, mut keep_going: impl FnMut(&Self, usize) -> bool) {
        while self.frames.len() > 1 {
            let Some(len) = self.frames.front().map(Vec::len) else {
                return;
            };
            if !keep_going(self, len) {
                return;
            }
            self.frames.pop_front();
            self.samples -= len;
            self.dropped += len as u64;
        }
    }
}

#[derive(Debug)]
pub(crate) struct FrameQueue {
    inner: Mutex<Inner>,
    ready: Condvar,
    high: usize,
    target: usize,
    window: usize,
}

fn ms_to_samples(ms: u64) -> usize {
    (ms * u64::from(rovsti_wa_proto::audio::SAMPLE_RATE) / 1_000) as usize
}

impl FrameQueue {
    pub(crate) fn new(limits: Limits) -> Arc<Self> {
        Arc::new(Self {
            inner: Mutex::default(),
            ready: Condvar::new(),
            high: ms_to_samples(limits.high_ms),
            target: ms_to_samples(limits.target_ms),
            window: ms_to_samples(WINDOW_MS),
        })
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Puts a frame in. Never waits, and cuts the oldest audio as the module
    /// docs say (the new frame always stays).
    pub(crate) fn push(&self, frame: Vec<i16>) -> Result<(), Closed> {
        let waker = {
            let mut q = self.lock();
            if q.closed {
                return Err(Closed);
            }
            q.pushed += 1;
            q.waiting_sum += q.samples as u64;
            q.waiting_max = q.waiting_max.max(q.samples);
            let waiting = q.samples;
            q.window_min = Some(q.window_min.map_or(waiting, |least| least.min(waiting)));
            q.window += frame.len();
            q.samples += frame.len();
            q.frames.push_back(frame);
            if q.samples > self.high {
                let target = self.target;
                q.cut(|q, _| q.samples > target);
                q.window = 0;
                q.window_min = None;
            } else if q.window >= self.window {
                // Never less than this waited: the reader does not need it.
                let mut surplus = q.window_min.unwrap_or(0).saturating_sub(self.target);
                q.cut(|_, len| {
                    let fits = len <= surplus;
                    surplus = surplus.saturating_sub(len);
                    fits
                });
                q.window = 0;
                q.window_min = None;
            }
            q.waker.take()
        };
        self.ready.notify_one();
        if let Some(waker) = waker {
            waker.wake();
        }
        Ok(())
    }

    fn pop_locked(q: &mut Inner) -> Result<Vec<i16>, RecvError> {
        match q.frames.pop_front() {
            Some(frame) => {
                q.samples -= frame.len();
                Ok(frame)
            }
            None if q.closed => Err(RecvError::Closed),
            None => Err(RecvError::Empty),
        }
    }

    /// The oldest frame, if one waits. Frames already queued still come out
    /// after a close.
    pub(crate) fn try_pop(&self) -> Result<Vec<i16>, RecvError> {
        Self::pop_locked(&mut self.lock())
    }

    /// The oldest frame, waiting up to `wait` for one.
    pub(crate) fn pop_timeout(&self, wait: Duration) -> Result<Vec<i16>, RecvError> {
        let deadline = Instant::now() + wait;
        let mut q = self.lock();
        loop {
            match Self::pop_locked(&mut q) {
                Err(RecvError::Empty) => {}
                other => return other,
            }
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return Err(RecvError::Empty);
            }
            q = self
                .ready
                .wait_timeout(q, left)
                .unwrap_or_else(|e| e.into_inner())
                .0;
        }
    }

    fn poll_pop(&self, cx: &mut Context<'_>) -> Poll<Option<Vec<i16>>> {
        let mut q = self.lock();
        match Self::pop_locked(&mut q) {
            Ok(frame) => Poll::Ready(Some(frame)),
            Err(RecvError::Closed) => Poll::Ready(None),
            Err(RecvError::Empty) => {
                q.waker = Some(cx.waker().clone());
                Poll::Pending
            }
        }
    }

    /// Ends the queue: nothing more goes in, and readers end once it is empty.
    pub(crate) fn close(&self) {
        let waker = {
            let mut q = self.lock();
            q.closed = true;
            q.waker.take()
        };
        self.ready.notify_all();
        if let Some(waker) = waker {
            waker.wake();
        }
    }

    pub(crate) fn is_closed(&self) -> bool {
        self.lock().closed
    }

    /// What the queue saw since the last call; the counters start again.
    pub(crate) fn take_stats(&self) -> QueueStats {
        let mut q = self.lock();
        let stats = QueueStats {
            frames: q.pushed,
            mean_ms: samples_ms(q.waiting_sum.checked_div(q.pushed).unwrap_or(0) as usize),
            max_ms: samples_ms(q.waiting_max),
            dropped_ms: samples_ms(q.dropped as usize),
            now_ms: samples_ms(q.samples),
        };
        q.pushed = 0;
        q.waiting_sum = 0;
        q.waiting_max = 0;
        q.dropped = 0;
        stats
    }
}

/// A [`FrameQueue`] as a stream of frames; it ends when the queue is closed
/// and empty.
pub(crate) struct Frames<'a>(pub(crate) &'a FrameQueue);

impl Stream for Frames<'_> {
    type Item = Vec<i16>;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        self.0.poll_pop(cx)
    }
}

/// The microphone side of a call's audio: frames of 960 samples go in.
/// Dropping it ends the audio.
#[derive(Debug)]
pub struct Mic(pub(crate) Arc<FrameQueue>);

impl Mic {
    /// Queues one microphone frame for the peer. Never waits: when the
    /// socket is behind, the oldest queued frames are dropped instead (see
    /// [`MIC_LIMITS`]). Fails only when the audio is over.
    pub fn try_send(&self, frame: Vec<i16>) -> Result<(), Closed> {
        self.0.push(frame)
    }

    /// The audio is over.
    pub fn is_closed(&self) -> bool {
        self.0.is_closed()
    }
}

impl Drop for Mic {
    fn drop(&mut self) {
        self.0.close();
    }
}

/// The peer's voice: frames of 320, 640 or 960 samples come out, oldest
/// first. Clones read the same queue. It is a [`Stream`] too, which ends when
/// the call's audio ends.
#[derive(Clone, Debug)]
pub struct Speaker(pub(crate) Arc<FrameQueue>);

impl Speaker {
    /// The next frame if one waits.
    pub fn try_recv(&self) -> Result<Vec<i16>, RecvError> {
        self.0.try_pop()
    }

    /// The next frame, waiting up to `wait` for it. For a player thread:
    /// the frame is handed over the moment it arrives, without polling.
    pub fn recv_timeout(&self, wait: Duration) -> Result<Vec<i16>, RecvError> {
        self.0.pop_timeout(wait)
    }
}

impl Stream for Speaker {
    type Item = Vec<i16>;

    fn poll_next(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<Option<Self::Item>> {
        self.0.poll_pop(cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures::StreamExt;
    use rovsti_wa_proto::audio::{FRAME_SAMPLES, STEP_SAMPLES};

    fn step(value: i16) -> Vec<i16> {
        vec![value; STEP_SAMPLES]
    }

    fn drain(queue: &FrameQueue) -> Vec<i16> {
        std::iter::from_fn(|| queue.try_pop().ok())
            .map(|f| f[0])
            .collect()
    }

    #[test]
    fn frames_come_out_in_order_and_nothing_is_cut_below_the_high_mark() {
        let queue = FrameQueue::new(SPEAKER_LIMITS);
        // 120 ms: exactly the high mark, so nothing goes.
        for i in 0..6 {
            queue.push(step(i)).unwrap();
        }
        assert_eq!(queue.take_stats().dropped_ms, 0);
        assert_eq!(drain(&queue), vec![0, 1, 2, 3, 4, 5]);
        assert_eq!(queue.try_pop(), Err(RecvError::Empty));
    }

    /// A stall, then everything at once: the old audio goes and the queue is
    /// back at the target, with the newest frames.
    #[test]
    fn a_burst_drops_the_oldest_and_drains_to_the_target() {
        let queue = FrameQueue::new(SPEAKER_LIMITS);
        for i in 0..7 {
            queue.push(step(i)).unwrap();
        }
        // The seventh frame made 140 ms: cut to 40 ms, the two newest.
        let stats = queue.take_stats();
        assert_eq!((stats.now_ms, stats.dropped_ms), (40, 100));
        assert_eq!(stats.max_ms, 120);
        assert_eq!(drain(&queue), vec![5, 6]);
    }

    /// A burst that ended below the high mark leaves frames standing: the
    /// reader takes one frame per frame and never catches up. Within two
    /// windows the surplus is cut and the queue is at the target again.
    #[test]
    fn a_standing_queue_is_cut_back_to_the_target() {
        let queue = FrameQueue::new(SPEAKER_LIMITS);
        for i in 0..50 {
            queue.push(step(i)).unwrap();
        }
        // The burst ended with 100 ms waiting, under the high mark.
        assert_eq!(queue.take_stats().now_ms, 100);
        for i in 50..150 {
            queue.push(step(i)).unwrap();
            queue.try_pop().unwrap();
        }
        // Two seconds of real-time reading later: 60 ms of old audio went.
        let stats = queue.take_stats();
        assert_eq!((stats.dropped_ms, stats.now_ms), (60, 40));
        assert_eq!(stats.max_ms, 100);
        // And it stays there: the reader is two frames behind, no more.
        for i in 150..250 {
            queue.push(step(i)).unwrap();
            assert_eq!(queue.try_pop().unwrap()[0], i - 2);
        }
        let stats = queue.take_stats();
        assert_eq!((stats.dropped_ms, stats.now_ms), (0, 40));
        assert_eq!((stats.mean_ms, stats.max_ms), (40, 40));
    }

    /// A healthy queue (the reader empties it between frames, with some
    /// jitter) is never cut.
    #[test]
    fn a_queue_the_reader_empties_now_and_then_is_left_alone() {
        let queue = FrameQueue::new(SPEAKER_LIMITS);
        for i in 0..1_000i32 {
            queue.push(step((i % 100) as i16)).unwrap();
            // Every tenth round the reader is late, then takes everything.
            if i % 10 != 0 {
                while queue.try_pop().is_ok() {}
            }
        }
        assert_eq!(queue.take_stats().dropped_ms, 0);
    }

    /// The sender's clock runs a little fast: 51 frames per 50 taken. The
    /// surplus goes 20 ms at a time; the queue never reaches the high mark.
    #[test]
    fn clock_drift_never_builds_a_standing_delay() {
        let queue = FrameQueue::new(SPEAKER_LIMITS);
        let mut worst = 0;
        for i in 0..5_000i32 {
            queue.push(step((i % 100) as i16)).unwrap();
            if i % 51 != 0 {
                queue.try_pop().unwrap();
            }
            worst = worst.max(queue.lock().samples);
        }
        assert!(samples_ms(worst) < SPEAKER_LIMITS.high_ms, "{worst}");
        // 99 frames too many arrived; all but the few still waiting went.
        let stats = queue.take_stats();
        assert!((1_800..=1_980).contains(&stats.dropped_ms), "{stats:?}");
        assert!(stats.now_ms <= 80, "{stats:?}");
    }

    /// Whole 60 ms frames of an old gateway count by their length, and a cut
    /// always leaves the newest frame.
    #[test]
    fn frames_are_counted_by_length_and_one_always_stays() {
        let queue = FrameQueue::new(SPEAKER_LIMITS);
        queue.push(vec![1; FRAME_SAMPLES]).unwrap();
        queue.push(vec![2; FRAME_SAMPLES]).unwrap();
        assert_eq!(queue.take_stats().now_ms, 120);
        queue.push(vec![3; FRAME_SAMPLES]).unwrap();
        let stats = queue.take_stats();
        assert_eq!((stats.now_ms, stats.dropped_ms), (60, 120));
        assert_eq!(drain(&queue), vec![3]);
    }

    #[test]
    fn the_microphone_queue_keeps_the_newest_60_ms() {
        let queue = FrameQueue::new(MIC_LIMITS);
        for i in 0..5 {
            queue.push(vec![i; FRAME_SAMPLES]).unwrap();
        }
        assert_eq!(drain(&queue), vec![4]);
    }

    #[test]
    fn a_closed_queue_hands_out_what_it_has_and_then_ends() {
        let queue = FrameQueue::new(SPEAKER_LIMITS);
        queue.push(step(1)).unwrap();
        queue.close();
        assert_eq!(queue.push(step(2)), Err(Closed));
        assert_eq!(queue.try_pop().unwrap()[0], 1);
        assert_eq!(queue.try_pop(), Err(RecvError::Closed));
        assert_eq!(
            queue.pop_timeout(Duration::from_secs(5)),
            Err(RecvError::Closed)
        );
    }

    /// The player thread waits inside the pull and gets the frame the moment
    /// it arrives; with nothing coming it gives up after the wait.
    #[test]
    fn a_waiting_reader_wakes_on_a_frame_and_on_the_close() {
        let queue = FrameQueue::new(SPEAKER_LIMITS);
        let started = Instant::now();
        assert_eq!(
            queue.pop_timeout(Duration::from_millis(30)),
            Err(RecvError::Empty)
        );
        assert!(started.elapsed() >= Duration::from_millis(30));

        let reader = {
            let queue = Arc::clone(&queue);
            std::thread::spawn(move || {
                let first = queue.pop_timeout(Duration::from_secs(10));
                let second = queue.pop_timeout(Duration::from_secs(10));
                (first, second, Instant::now())
            })
        };
        std::thread::sleep(Duration::from_millis(20));
        queue.push(step(9)).unwrap();
        std::thread::sleep(Duration::from_millis(20));
        queue.close();
        let (first, second, done) = reader.join().unwrap();
        assert_eq!(first.unwrap()[0], 9);
        assert_eq!(second, Err(RecvError::Closed));
        assert!(done.duration_since(started) < Duration::from_secs(5));
    }

    #[test]
    fn the_stream_yields_frames_and_ends_with_the_queue() {
        let queue = FrameQueue::new(MIC_LIMITS);
        let mic = Mic(Arc::clone(&queue));
        let mut speaker = Speaker(Arc::clone(&queue));
        mic.try_send(step(4)).unwrap();
        futures::executor::block_on(async {
            assert_eq!(speaker.next().await.unwrap()[0], 4);
            let waiting = std::thread::spawn(move || {
                std::thread::sleep(Duration::from_millis(20));
                mic.try_send(step(5)).unwrap();
                // Dropping the microphone ends the audio.
            });
            assert_eq!(speaker.next().await.unwrap()[0], 5);
            assert_eq!(speaker.next().await, None);
            waiting.join().unwrap();
        });
        assert!(queue.is_closed());
    }
}
