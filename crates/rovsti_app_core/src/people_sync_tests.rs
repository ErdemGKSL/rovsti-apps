//! Unit tests of the people sync planner: a fake clock, a fixed `rand`, no network.

use super::*;
use crate::people::{self, FileInfo, Update};
use crate::wa_link::avatars;

const NOW: i64 = 1_790_000_000_000;
const R: u32 = 20; // jitter midpoint: gap * 100 / 100

fn link() -> LinkId {
    LinkId::new("inst-1", "https://gw.example.test/")
}

/// `n` fake numbers `+90555000xxxx`.
fn numbers(n: usize) -> Vec<String> {
    (0..n).map(|i| format!("+90555000{i:04}")).collect()
}

fn jid(number: &str) -> String {
    jid_of(number).unwrap()
}

/// A world: checkpoint, book and the clock.
struct World {
    cp: Checkpoint,
    book: Book,
    now: i64,
}

impl World {
    fn new() -> Self {
        Self {
            cp: Checkpoint::default(),
            book: Book::default(),
            now: NOW,
        }
    }

    fn begin(
        &mut self,
        nums: &[String],
        start: Start,
        slice: Option<i64>,
    ) -> Result<Session, Refused> {
        begin(
            &mut self.cp,
            &self.book,
            link(),
            nums,
            start,
            slice,
            self.now,
        )
    }

    fn next(&mut self, s: &mut Session) -> Step {
        next(&mut self.cp, s, &self.book, self.now, R)
    }

    fn report(&mut self, s: &mut Session, r: Report) -> Committed {
        report(&mut self.cp, s, &mut self.book, self.now, R, r)
    }

    /// Sleeps are taken at once (the clock moves).
    fn next_awake(&mut self, s: &mut Session) -> Step {
        loop {
            match self.next(s) {
                Step::Sleep { ms, .. } => self.now += ms,
                step => return step,
            }
        }
    }

    fn run(&self) -> &Run {
        self.cp.run.as_ref().unwrap()
    }
}

/// A gateway where every second number is on WhatsApp, every profile is answered, every picture is new.
fn answer(w: &mut World, s: &mut Session, step: &Step) -> Committed {
    match step {
        Step::Lookup { jids } => {
            // on WhatsApp: the numbers whose last digit is even
            let found = jids
                .iter()
                .filter(|j| {
                    j.split('@')
                        .next()
                        .is_some_and(|u| u.ends_with(['0', '2', '4', '6', '8']))
                })
                .map(|j| Update {
                    jid: j.clone(),
                    ..Update::default()
                })
                .collect();
            w.report(
                s,
                Report::Lookup {
                    asked: jids.clone(),
                    found,
                },
            )
        }
        Step::Profiles { jids } => w.report(
            s,
            Report::Profiles {
                asked: jids.clone(),
                answered: jids
                    .iter()
                    .map(|j| Update {
                        jid: j.clone(),
                        push_name: Some("P".into()),
                        picture_id: Some("1".into()),
                        ..Update::default()
                    })
                    .collect(),
                unanswered: vec![],
            },
        ),
        Step::PlanPictures => w.report(
            s,
            Report::Files {
                files: vec![],
                max_bytes: 1 << 24,
            },
        ),
        Step::Picture { jid, .. } => w.report(
            s,
            Report::Picture {
                jid: jid.clone(),
                result: PictureResult::Stored {
                    picture_id: "1".into(),
                    file: avatars::file_name(jid, "1"),
                },
            },
        ),
        other => panic!("not a request: {other:?}"),
    }
}

/// Drives the session until it stops; returns the steps (without sleeps) and the stop.
fn drive(w: &mut World, s: &mut Session, limit: usize) -> (Vec<Step>, Stopped) {
    let mut steps = Vec::new();
    for _ in 0..limit {
        let step = w.next_awake(s);
        if let Step::Stop { stopped } = step {
            return (steps, stopped);
        }
        answer(w, s, &step);
        steps.push(step);
    }
    panic!("did not stop in {limit} steps");
}

fn kind(step: &Step) -> &'static str {
    match step {
        Step::Lookup { .. } => "lookup",
        Step::Profiles { .. } => "profiles",
        Step::PlanPictures => "plan",
        Step::Picture { .. } => "picture",
        Step::Sleep { .. } => "sleep",
        Step::Stop { .. } => "stop",
    }
}

#[test]
fn fresh_run_walks_lookup_profiles_pictures() {
    let mut w = World::new();
    let nums = numbers(40);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let (steps, stopped) = drive(&mut w, &mut s, 500);
    assert_eq!(stopped, Stopped::Done);
    let mut kinds: Vec<&str> = steps.iter().map(kind).collect();
    kinds.dedup();
    assert_eq!(kinds, ["lookup", "profiles", "plan", "picture"]);
    let people = w.book.people.iter().filter(|p| p.on_whatsapp).count();
    assert_eq!(people, 20);
    assert!(w.cp.run.is_none(), "run cleared");
    let last = w.cp.last.unwrap();
    assert_eq!((last.people, last.downloaded), (20, 20));
    assert!(w.book.people.iter().all(|p| p.picture_file.is_some()));
}

#[test]
fn batches_are_at_most_usync_batch() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(40), Start::Restart, None).unwrap();
    let (steps, _) = drive(&mut w, &mut s, 500);
    for step in &steps {
        if let Step::Lookup { jids } | Step::Profiles { jids } = step {
            assert!(jids.len() <= USYNC_BATCH as usize && !jids.is_empty());
        }
    }
    let lookups = steps.iter().filter(|s| kind(s) == "lookup").count();
    assert_eq!(lookups, 3, "40 numbers in batches of 15");
}

#[test]
fn gap_and_jitter_bounds() {
    assert_eq!(jitter(20_000, 20), 20_000);
    assert_eq!(jitter(20_000, 0), 16_000);
    assert_eq!(jitter(20_000, 40), 24_000);
    for r in 0..500 {
        let g = jitter(USYNC_GAP_MS, r);
        assert!((16_000..=24_000).contains(&g), "{g}");
    }
}

#[test]
fn no_burst_first_two_batches_are_a_gap_apart() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(40), Start::Restart, None).unwrap();
    let first = w.next(&mut s);
    assert_eq!(kind(&first), "lookup");
    answer(&mut w, &mut s, &first);
    let t1 = w.now;
    match w.next(&mut s) {
        Step::Sleep { ms, why } => {
            assert_eq!(why, Why::Pace);
            assert_eq!(ms, USYNC_GAP_MS);
        }
        other => panic!("expected a pace sleep, got {other:?}"),
    }
    let second = w.next_awake(&mut s);
    assert_eq!(kind(&second), "lookup");
    assert_eq!(w.now - t1, USYNC_GAP_MS);
}

#[test]
fn lookups_and_profiles_share_the_usync_gap() {
    let mut w = World::new();
    // 10 numbers: one lookup batch, then the profiles must still wait the gap.
    let mut s = w.begin(&numbers(10), Start::Restart, None).unwrap();
    let first = w.next(&mut s);
    answer(&mut w, &mut s, &first);
    match w.next(&mut s) {
        Step::Sleep { ms, why: Why::Pace } => assert_eq!(ms, USYNC_GAP_MS),
        other => panic!("expected the shared gap, got {other:?}"),
    }
    assert_eq!(kind(&w.next_awake(&mut s)), "profiles");
}

#[test]
fn rate_limit_doubles_gap_halves_batch() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(40), Start::Restart, None).unwrap();
    let _ = w.next(&mut s);
    w.report(
        &mut s,
        Report::RateLimited {
            retry_after_ms: None,
            source: LimitSource::Whatsapp,
        },
    );
    assert_eq!(w.cp.pace.usync_gap_ms, 2 * USYNC_GAP_MS);
    assert_eq!(w.cp.pace.usync_batch, USYNC_BATCH / 2);
    assert_eq!(w.cp.pace.strikes, 1);
    match w.next(&mut s) {
        Step::Sleep { ms, why } => {
            assert_eq!(why, Why::RateLimit);
            // BACKOFF_BASE plus R % 26 = 20 % jitter
            assert_eq!(ms, BACKOFF_BASE_MS * 120 / 100);
        }
        other => panic!("{other:?}"),
    }
    // the same batch again, re-cut at the new size
    match w.next_awake(&mut s) {
        Step::Lookup { jids } => {
            assert_eq!(jids.len(), (USYNC_BATCH / 2) as usize);
            assert_eq!(jids[0], jid(&numbers(1)[0]));
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn retry_after_is_never_undercut() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(5), Start::Restart, None).unwrap();
    let _ = w.next(&mut s);
    w.report(
        &mut s,
        Report::RateLimited {
            retry_after_ms: Some(75_000),
            source: LimitSource::Gateway,
        },
    );
    assert!(w.cp.pace.not_before_ms >= w.now + 75_000);
    assert_eq!(w.cp.pace.source, Some(LimitSource::Gateway));
}

#[test]
fn backoff_caps_at_max() {
    let mut pace = Pace {
        strikes: 30,
        ..Pace::default()
    };
    let mut run = Run::new(NOW, Mode::Full, Stage::Lookup);
    rate_limited(
        &mut pace,
        &mut run,
        None,
        NOW,
        0,
        None,
        LimitSource::Whatsapp,
        false,
    );
    assert_eq!(pace.not_before_ms, NOW + BACKOFF_MAX_MS);
    assert_eq!(pace.usync_gap_ms, 2 * USYNC_GAP_MS);
    for _ in 0..10 {
        rate_limited(
            &mut pace,
            &mut run,
            None,
            NOW,
            0,
            None,
            LimitSource::Whatsapp,
            false,
        );
    }
    assert_eq!(pace.usync_gap_ms, USYNC_GAP_MAX_MS);
    assert_eq!(pace.usync_batch, USYNC_BATCH_MIN);
    // a hint longer than the cap is still honoured
    rate_limited(
        &mut pace,
        &mut run,
        None,
        NOW,
        0,
        Some(3_600_000),
        LimitSource::Whatsapp,
        false,
    );
    assert_eq!(pace.not_before_ms, NOW + 3_600_000);
}

#[test]
fn short_wait_sleeps_inline_long_wait_stops() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(5), Start::Restart, None).unwrap();
    let _ = w.next(&mut s);
    w.report(
        &mut s,
        Report::RateLimited {
            retry_after_ms: None,
            source: LimitSource::Whatsapp,
        },
    );
    assert_eq!(w.run().phase, Phase::Running, "a minute sleeps inline");
    let _ = w.next_awake(&mut s);
    w.report(
        &mut s,
        Report::RateLimited {
            retry_after_ms: Some(10 * 60_000),
            source: LimitSource::Whatsapp,
        },
    );
    assert!(matches!(w.run().phase, Phase::Waiting { .. }));
    match w.next(&mut s) {
        Step::Stop {
            stopped: Stopped::Waiting { until_ms, source },
        } => {
            assert!(until_ms >= w.now + 10 * 60_000);
            assert_eq!(source, LimitSource::Whatsapp);
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn a_wait_past_the_slice_deadline_stops() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(5), Start::Restart, Some(30_000)).unwrap();
    let _ = w.next(&mut s);
    w.report(
        &mut s,
        Report::RateLimited {
            retry_after_ms: None,
            source: LimitSource::Whatsapp,
        },
    );
    assert!(
        matches!(w.run().phase, Phase::Waiting { .. }),
        "a minute does not fit 30 s"
    );
}

#[test]
fn a_second_strike_stops_the_slice() {
    // The backoff doubles past the inline limit on the second strike, so MAX_STRIKES is only a guard.
    let mut w = World::new();
    let mut s = w.begin(&numbers(5), Start::Restart, None).unwrap();
    for strike in 1..=2 {
        let step = w.next_awake(&mut s);
        assert_eq!(kind(&step), "lookup", "strike {strike}");
        w.report(
            &mut s,
            Report::RateLimited {
                retry_after_ms: Some(1_000),
                source: LimitSource::Whatsapp,
            },
        );
    }
    assert!(matches!(w.run().phase, Phase::Waiting { .. }));
    const { assert!(MAX_STRIKES >= 2) };
    assert!(matches!(
        w.next(&mut s),
        Step::Stop {
            stopped: Stopped::Waiting { .. }
        }
    ));
}

#[test]
fn recovery_restores_base_pace() {
    let mut pace = Pace {
        usync_gap_ms: USYNC_GAP_MAX_MS,
        usync_batch: USYNC_BATCH_MIN,
        strikes: 2,
        ..Pace::default()
    };
    for i in 1..RECOVER_AFTER {
        success(&mut pace, false, 1, NOW);
        assert_eq!(pace.strikes, 2, "success {i}");
        assert_eq!(pace.usync_gap_ms, USYNC_GAP_MAX_MS);
    }
    success(&mut pace, false, 1, NOW);
    assert_eq!(pace.strikes, 0);
    for _ in 0..200 {
        success(&mut pace, false, 1, NOW);
    }
    assert_eq!(pace.usync_gap_ms, USYNC_GAP_MS, "never below the base");
    assert_eq!(pace.usync_batch, USYNC_BATCH, "never above the base batch");
}

#[test]
fn pace_survives_save_and_load() {
    let dir = std::env::temp_dir().join(format!("people-sync-pace-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    let mut w = World::new();
    let mut s = w.begin(&numbers(5), Start::Restart, None).unwrap();
    let _ = w.next(&mut s);
    w.report(
        &mut s,
        Report::RateLimited {
            retry_after_ms: None,
            source: LimitSource::Whatsapp,
        },
    );
    save(&dir, &w.cp).unwrap();
    let back = load(&dir);
    assert_eq!(back, w.cp);
    assert_eq!(back.pace.usync_gap_ms, 2 * USYNC_GAP_MS);
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn resume_skips_done_sets() {
    let mut w = World::new();
    let nums = numbers(40);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let first = w.next_awake(&mut s);
    answer(&mut w, &mut s, &first);
    let Step::Lookup { jids: asked_first } = first else {
        panic!()
    };
    stop(&mut w.cp, Some(&mut s), Interrupt::Cancelled);
    assert_eq!(
        w.run().phase,
        Phase::Interrupted {
            reason: Interrupt::Cancelled
        }
    );
    w.now += 60_000;
    let mut s = w.begin(&nums, Start::Resume, None).unwrap();
    assert_eq!(w.run().slices, 2);
    let (steps, stopped) = drive(&mut w, &mut s, 500);
    assert_eq!(stopped, Stopped::Done);
    for step in steps {
        if let Step::Lookup { jids } = step {
            assert!(jids.iter().all(|j| !asked_first.contains(j)), "asked twice");
        }
    }
}

#[test]
fn resume_refused_before_not_before() {
    let mut w = World::new();
    let nums = numbers(5);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let _ = w.next(&mut s);
    w.report(
        &mut s,
        Report::RateLimited {
            retry_after_ms: Some(20 * 60_000),
            source: LimitSource::Whatsapp,
        },
    );
    let until = w.cp.pace.not_before_ms;
    for start in [
        Start::Resume,
        Start::Restart,
        Start::Auto,
        Start::LivePictures,
    ] {
        assert_eq!(
            w.begin(&nums, start, None).unwrap_err(),
            Refused::NotBefore { until_ms: until },
            "{start:?}"
        );
    }
    w.now = until;
    assert!(w.begin(&nums, Start::Resume, None).is_ok());
    assert_eq!(w.run().phase, Phase::Running);
}

#[test]
fn resume_without_a_run_is_refused() {
    let mut w = World::new();
    assert_eq!(
        w.begin(&numbers(3), Start::Resume, None).unwrap_err(),
        Refused::NothingToResume
    );
}

#[test]
fn restart_clears_done_sets_keeps_book() {
    let mut w = World::new();
    let nums = numbers(20);
    let mut s = w.begin(&nums, Start::Auto, None).unwrap();
    let step = w.next_awake(&mut s);
    answer(&mut w, &mut s, &step);
    let people = w.book.people.len();
    assert!(people > 0);
    stop(&mut w.cp, Some(&mut s), Interrupt::Cancelled);
    let _ = w.begin(&nums, Start::Restart, None).unwrap();
    assert!(w.run().looked.is_empty());
    assert_eq!(w.run().mode, Mode::Full);
    assert_eq!(w.book.people.len(), people, "book kept");
}

#[test]
fn auto_resumes_then_incremental() {
    let mut w = World::new();
    let nums = numbers(20);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let step = w.next_awake(&mut s);
    answer(&mut w, &mut s, &step);
    stop(&mut w.cp, Some(&mut s), Interrupt::SliceBudget);
    let id = w.run().id;
    w.now += 1000;
    let mut s = w.begin(&nums, Start::Auto, None).unwrap();
    assert_eq!(w.run().id, id, "resumed");
    assert_eq!(w.run().mode, Mode::Full, "kept its mode");
    let (_, stopped) = drive(&mut w, &mut s, 500);
    assert_eq!(stopped, Stopped::Done);
    w.now += 1000;
    let _ = w.begin(&nums, Start::Auto, None).unwrap();
    assert_ne!(w.run().id, id);
    assert_eq!(w.run().mode, Mode::Incremental);
}

#[test]
fn run_older_than_24h_is_not_resumable() {
    let mut w = World::new();
    let nums = numbers(20);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let step = w.next_awake(&mut s);
    answer(&mut w, &mut s, &step);
    stop(&mut w.cp, Some(&mut s), Interrupt::Network);
    w.now += RESUME_MAX_AGE_MS + 1;
    assert!(status(&w.cp, w.now).run.is_none(), "no Continue offered");
    assert_eq!(
        w.begin(&nums, Start::Resume, None).unwrap_err(),
        Refused::NothingToResume
    );
    let _ = w.begin(&nums, Start::Auto, None).unwrap();
    assert_eq!(w.run().mode, Mode::Incremental);
    assert!(w.run().looked.is_empty());
}

#[test]
fn contact_added_between_slices_is_asked() {
    let mut w = World::new();
    let mut nums = numbers(5);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let step = w.next_awake(&mut s);
    answer(&mut w, &mut s, &step);
    stop(&mut w.cp, Some(&mut s), Interrupt::SliceBudget);
    nums.push("+905559990001".into());
    let mut s = w.begin(&nums, Start::Resume, None).unwrap();
    match w.next_awake(&mut s) {
        Step::Lookup { jids } => assert_eq!(jids, [jid("+905559990001")]),
        other => panic!("{other:?}"),
    }
}

#[test]
fn contact_removed_between_slices_is_dropped() {
    let mut w = World::new();
    let nums = numbers(40);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let step = w.next_awake(&mut s);
    answer(&mut w, &mut s, &step);
    stop(&mut w.cp, Some(&mut s), Interrupt::SliceBudget);
    // keep only the first five (already asked) and number 30
    let kept: Vec<String> = nums[..5].iter().chain([&nums[30]]).cloned().collect();
    let mut s = w.begin(&kept, Start::Resume, None).unwrap();
    assert_eq!(
        w.run().looked.len(),
        5,
        "done set intersected with the targets"
    );
    match w.next_awake(&mut s) {
        Step::Lookup { jids } => assert_eq!(jids, [jid(&nums[30])]),
        other => panic!("{other:?}"),
    }
    assert!(!w.cp.negatives.contains_key(&jid(&nums[10])));
}

#[test]
fn link_change_resets_run_negatives_and_pace() {
    let mut w = World::new();
    let nums = numbers(5);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let step = w.next_awake(&mut s);
    answer(&mut w, &mut s, &step);
    w.cp.pace.usync_gap_ms = 99_000;
    stop(&mut w.cp, Some(&mut s), Interrupt::Cancelled);
    assert!(!w.cp.negatives.is_empty());
    let other = LinkId::new("inst-2", "https://gw.example.test");
    let _ = begin(
        &mut w.cp,
        &w.book,
        other.clone(),
        &nums,
        Start::Auto,
        None,
        w.now,
    )
    .unwrap();
    assert!(w.cp.negatives.is_empty());
    assert_eq!(w.cp.pace.usync_gap_ms, USYNC_GAP_MS);
    assert_eq!(
        w.run().mode,
        Mode::Incremental,
        "a new run, not the old one"
    );
    assert_eq!(w.cp.link, Some(other));
    // the same gateway spelled with a trailing slash is the same link
    assert_eq!(link(), LinkId::new("inst-1", "https://gw.example.test"));
}

#[test]
fn fresh_process_turns_running_into_interrupted() {
    let mut w = World::new();
    let _s = w.begin(&numbers(5), Start::Restart, None).unwrap();
    assert_eq!(w.run().phase, Phase::Running);
    assert!(after_process_start(&mut w.cp));
    assert_eq!(
        w.run().phase,
        Phase::Interrupted {
            reason: Interrupt::ProcessDeath
        }
    );
    assert!(!after_process_start(&mut w.cp), "only once");
    assert!(status(&w.cp, w.now).run.unwrap().resumable);
}

#[test]
fn slice_deadline_interrupts_with_progress() {
    let mut w = World::new();
    let mut s = w
        .begin(&numbers(400), Start::Restart, Some(WORKER_SLICE_MS))
        .unwrap();
    let begun = w.now;
    loop {
        match w.next_awake(&mut s) {
            Step::Stop { stopped } => {
                assert_eq!(
                    stopped,
                    Stopped::Interrupted {
                        reason: Interrupt::SliceBudget
                    }
                );
                break;
            }
            step => {
                answer(&mut w, &mut s, &step);
            }
        }
    }
    assert!(w.now - begun <= WORKER_SLICE_MS, "never past the deadline");
    let st = status(&w.cp, w.now).run.unwrap();
    assert!(st.resumable);
    assert!(st.progress.done > 0 && st.progress.total >= st.progress.done);
}

#[test]
fn unanswered_profiles_are_not_done() {
    let mut w = World::new();
    let nums = numbers(2);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let a = jid(&nums[0]);
    let b = jid(&nums[1]);
    let _ = w.next_awake(&mut s);
    w.report(
        &mut s,
        Report::Lookup {
            asked: vec![a.clone(), b.clone()],
            found: vec![
                Update {
                    jid: a.clone(),
                    ..Update::default()
                },
                Update {
                    jid: b.clone(),
                    ..Update::default()
                },
            ],
        },
    );
    let step = w.next_awake(&mut s);
    assert_eq!(
        step,
        Step::Profiles {
            jids: vec![a.clone(), b.clone()]
        }
    );
    w.report(
        &mut s,
        Report::Profiles {
            asked: vec![a.clone(), b.clone()],
            answered: vec![Update {
                jid: a.clone(),
                push_name: Some("A".into()),
                ..Update::default()
            }],
            unanswered: vec![b.clone()],
        },
    );
    assert!(w.run().profiled.contains(&a));
    assert!(!w.run().profiled.contains(&b));
    assert_eq!(
        w.next_awake(&mut s),
        Step::Profiles {
            jids: vec![b.clone()]
        }
    );
}

#[test]
fn a_profile_left_unanswered_three_times_is_given_up() {
    let mut w = World::new();
    let nums = numbers(2);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let (a, b) = (jid(&nums[0]), jid(&nums[1]));
    let _ = w.next_awake(&mut s);
    let found = [&a, &b]
        .iter()
        .map(|j| Update {
            jid: (*j).clone(),
            ..Update::default()
        })
        .collect();
    w.report(
        &mut s,
        Report::Lookup {
            asked: vec![a.clone(), b.clone()],
            found,
        },
    );
    for _ in 0..MAX_UNANSWERED {
        let _ = w.next_awake(&mut s);
        w.report(
            &mut s,
            Report::Profiles {
                asked: vec![a.clone(), b.clone()],
                answered: vec![Update {
                    jid: a.clone(),
                    ..Update::default()
                }],
                unanswered: vec![b.clone()],
            },
        );
    }
    assert!(w.run().profiled.contains(&b));
    assert!(
        !w.cp.profiled_ms.contains_key(&b),
        "not remembered as answered"
    );
    assert_eq!(kind(&w.next_awake(&mut s)), "plan");
}

#[test]
fn all_unanswered_batch_counts_as_legacy_throttle() {
    let mut w = World::new();
    let nums = numbers(1);
    let a = jid(&nums[0]);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let _ = w.next_awake(&mut s);
    w.report(
        &mut s,
        Report::Lookup {
            asked: vec![a.clone()],
            found: vec![Update {
                jid: a.clone(),
                ..Update::default()
            }],
        },
    );
    let _ = w.next_awake(&mut s);
    w.report(
        &mut s,
        Report::Profiles {
            asked: vec![a.clone()],
            answered: vec![],
            unanswered: vec![a.clone()],
        },
    );
    assert_eq!(w.cp.pace.strikes, 1);
    assert_eq!(w.cp.pace.source, Some(LimitSource::GatewayLegacy));
    assert!(
        w.run().unanswered.is_empty(),
        "a throttle is not counted against the JID"
    );
    assert_eq!(w.run().stats.rate_limits, 1);
}

#[test]
fn negatives_skipped_incremental_asked_full() {
    let mut w = World::new();
    let nums = numbers(3);
    let mut s = w.begin(&nums, Start::Auto, None).unwrap();
    let step = w.next_awake(&mut s);
    w.report(
        &mut s,
        Report::Lookup {
            asked: match step {
                Step::Lookup { jids } => jids,
                _ => panic!(),
            },
            found: vec![],
        },
    );
    let (_, stopped) = drive(&mut w, &mut s, 50);
    assert_eq!(stopped, Stopped::Done);
    assert_eq!(w.cp.negatives.len(), 3);
    w.now += 3_600_000;
    let mut s = w.begin(&nums, Start::Auto, None).unwrap();
    assert_eq!(
        kind(&w.next_awake(&mut s)),
        "plan",
        "incremental skips fresh negatives"
    );
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    match w.next_awake(&mut s) {
        Step::Lookup { jids } => assert_eq!(jids.len(), 3, "a full run asks again"),
        other => panic!("{other:?}"),
    }
}

#[test]
fn negative_and_profile_ttls() {
    let mut w = World::new();
    let nums = numbers(4);
    let mut s = w.begin(&nums, Start::Auto, None).unwrap();
    let (_, stopped) = drive(&mut w, &mut s, 100);
    assert_eq!(stopped, Stopped::Done);
    let profiled = *w.cp.profiled_ms.values().min().unwrap();
    let last_profiled = *w.cp.profiled_ms.values().max().unwrap();
    let negative = *w.cp.negatives.values().min().unwrap();
    // fresh: nothing to look up or profile
    w.now = profiled + PROFILE_FRESH_MS - 1;
    let mut s = w.begin(&nums, Start::Auto, None).unwrap();
    assert_eq!(kind(&w.next_awake(&mut s)), "plan");
    let _ = drive(&mut w, &mut s, 100);
    // profiles are stale after a day, negatives only after a week
    w.now = last_profiled + PROFILE_FRESH_MS + 1;
    let mut s = w.begin(&nums, Start::Auto, None).unwrap();
    assert_eq!(kind(&w.next_awake(&mut s)), "profiles");
    let _ = drive(&mut w, &mut s, 100);
    w.now = negative + NEGATIVE_TTL_MS + 1;
    let mut s = w.begin(&nums, Start::Auto, None).unwrap();
    assert_eq!(kind(&w.next_awake(&mut s)), "lookup");
}

#[test]
fn picture_failures_cap() {
    let mut w = World::new();
    let nums = numbers(40);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let mut pictures = 0;
    loop {
        let step = w.next_awake(&mut s);
        match &step {
            Step::Stop { stopped } => {
                assert_eq!(*stopped, Stopped::Done);
                break;
            }
            Step::Picture { jid, .. } => {
                pictures += 1;
                w.report(
                    &mut s,
                    Report::Picture {
                        jid: jid.clone(),
                        result: PictureResult::Failed,
                    },
                );
            }
            _ => {
                answer(&mut w, &mut s, &step);
            }
        }
    }
    assert_eq!(pictures, MAX_FAILURES as usize);
}

#[test]
fn rate_limited_picture_is_not_a_failure() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(4), Start::Restart, None).unwrap();
    loop {
        let step = w.next_awake(&mut s);
        if let Step::Picture { .. } = step {
            break;
        }
        answer(&mut w, &mut s, &step);
    }
    let usync_gap = w.cp.pace.usync_gap_ms;
    w.report(
        &mut s,
        Report::RateLimited {
            retry_after_ms: None,
            source: LimitSource::Whatsapp,
        },
    );
    assert_eq!(w.run().picture_failures, 0);
    assert!(w.run().pictured.is_empty());
    assert_eq!(w.cp.pace.picture_gap_ms, 2 * PICTURE_GAP_MS);
    assert_eq!(
        w.cp.pace.usync_gap_ms, usync_gap,
        "the usync pace is not touched"
    );
    assert_eq!(
        kind(&w.next_awake(&mut s)),
        "picture",
        "the same picture again"
    );
}

#[test]
fn a_gateway_error_on_one_picture_fails_only_that_picture() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(4), Start::Restart, None).unwrap();
    let first = loop {
        let step = w.next_awake(&mut s);
        if let Step::Picture { jid, .. } = &step {
            break jid.clone();
        }
        answer(&mut w, &mut s, &step);
    };
    w.report(
        &mut s,
        Report::Error {
            error: ErrorKind::Gateway,
            message: "500".into(),
        },
    );
    assert_eq!(w.run().phase, Phase::Running);
    assert!(w.run().pictured.contains(&first));
    match w.next_awake(&mut s) {
        Step::Picture { jid, .. } => assert_ne!(jid, first),
        other => panic!("{other:?}"),
    }
}

#[test]
fn errors_map_to_phases() {
    let cases = [
        (
            ErrorKind::Network,
            Phase::Interrupted {
                reason: Interrupt::Network,
            },
        ),
        (
            ErrorKind::NotConnected,
            Phase::Interrupted {
                reason: Interrupt::WaDisconnected,
            },
        ),
        (
            ErrorKind::Auth,
            Phase::Failed {
                reason: FailReason::Auth,
                message: "m".into(),
            },
        ),
        (
            ErrorKind::Gateway,
            Phase::Failed {
                reason: FailReason::Gateway,
                message: "m".into(),
            },
        ),
        (
            ErrorKind::Core,
            Phase::Failed {
                reason: FailReason::Core,
                message: "m".into(),
            },
        ),
    ];
    for (error, phase) in cases {
        let mut w = World::new();
        let mut s = w.begin(&numbers(3), Start::Restart, None).unwrap();
        let _ = w.next(&mut s);
        w.report(
            &mut s,
            Report::Error {
                error,
                message: "m".into(),
            },
        );
        assert_eq!(w.run().phase, phase, "{error:?}");
        assert!(matches!(w.next(&mut s), Step::Stop { .. }));
        assert!(status(&w.cp, w.now).run.unwrap().resumable);
    }
}

#[test]
fn report_is_idempotent() {
    let mut w = World::new();
    let nums = numbers(6);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let step = w.next_awake(&mut s);
    let r = Report::Lookup {
        asked: match &step {
            Step::Lookup { jids } => jids.clone(),
            _ => panic!(),
        },
        found: vec![Update {
            jid: jid(&nums[0]),
            push_name: Some("A".into()),
            ..Update::default()
        }],
    };
    w.report(&mut s, r.clone());
    let (book, run, negatives) = (
        w.book.clone(),
        w.run().looked.clone(),
        w.cp.negatives.clone(),
    );
    let again = w.report(&mut s, r);
    assert_eq!(again.changed, 0);
    assert_eq!(w.book, book);
    assert_eq!(w.run().looked, run);
    assert_eq!(w.cp.negatives, negatives);
}

#[test]
fn crash_between_book_and_checkpoint_repeats_one_batch() {
    let mut w = World::new();
    let nums = numbers(30);
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let first = w.next_awake(&mut s);
    answer(&mut w, &mut s, &first);
    let saved_cp = w.cp.clone();
    let second = w.next_awake(&mut s);
    answer(&mut w, &mut s, &second);
    // the book holds both batches, the checkpoint only the first; the process dies
    let mut cp = saved_cp;
    assert!(after_process_start(&mut cp));
    w.cp = cp;
    let mut s = w.begin(&nums, Start::Auto, None).unwrap();
    let again = w.next_awake(&mut s);
    // the second batch's people on WhatsApp are in the book (skipped); its negatives are asked again
    match (&second, &again) {
        (Step::Lookup { jids: was }, Step::Lookup { jids: now }) => {
            assert!(
                now.iter().all(|j| was.contains(j)),
                "only the lost batch again"
            );
            assert!(!now.is_empty() && now.len() <= was.len());
        }
        other => panic!("{other:?}"),
    }
    let people = w.book.people.len();
    answer(&mut w, &mut s, &again);
    assert_eq!(w.book.people.len(), people, "the repeat changed nothing");
}

#[test]
fn damaged_or_oversized_file_reads_as_default() {
    let dir = std::env::temp_dir().join(format!("people-sync-damaged-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    assert_eq!(load(&dir), Checkpoint::default(), "missing");
    fs::write(path(&dir), b"{not json").unwrap();
    assert_eq!(load(&dir), Checkpoint::default(), "damaged");
    fs::write(path(&dir), vec![b' '; MAX_FILE_BYTES + 1]).unwrap();
    assert_eq!(load(&dir), Checkpoint::default(), "oversized");
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn newer_schema_reads_as_default() {
    let dir = std::env::temp_dir().join(format!("people-sync-newer-{}", std::process::id()));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).unwrap();
    fs::write(
        path(&dir),
        br#"{"schema_version":2,"negatives":{"1@s.whatsapp.net":5}}"#,
    )
    .unwrap();
    assert_eq!(load(&dir), Checkpoint::default());
    fs::write(
        path(&dir),
        br#"{"schema_version":1,"negatives":{"1@s.whatsapp.net":5}}"#,
    )
    .unwrap();
    assert_eq!(
        load(&dir).negatives.len(),
        1,
        "missing fields take their defaults"
    );
    let _ = fs::remove_dir_all(&dir);
}

#[test]
fn status_progress_per_stage() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(20), Start::Restart, None).unwrap();
    let st = status(&w.cp, w.now).run.unwrap();
    assert_eq!(
        (st.stage, st.progress),
        (Stage::Lookup, Progress { done: 0, total: 20 })
    );
    assert!(!st.resumable, "running");
    let step = w.next_awake(&mut s);
    let c = answer(&mut w, &mut s, &step);
    assert_eq!(
        c.progress,
        Progress {
            done: 15,
            total: 20
        }
    );
    assert_eq!(c.stage, Some(Stage::Lookup));
    let step = w.next_awake(&mut s);
    answer(&mut w, &mut s, &step);
    let step = w.next_awake(&mut s);
    assert_eq!(kind(&step), "profiles");
    let st = status(&w.cp, w.now).run.unwrap();
    assert_eq!(st.stage, Stage::Profiles);
    assert_eq!(st.progress, Progress { done: 0, total: 10 });
}

#[test]
fn live_pictures_do_not_touch_run() {
    let mut w = World::new();
    let nums = numbers(10);
    // a resumable run in the lookup stage, and a person whose picture changed
    let mut s = w.begin(&nums, Start::Restart, None).unwrap();
    let step = w.next_awake(&mut s);
    answer(&mut w, &mut s, &step);
    stop(&mut w.cp, Some(&mut s), Interrupt::SliceBudget);
    let stored = w.cp.run.clone();
    let negatives = w.cp.negatives.clone();
    let mut live = w
        .begin(&[], Start::LivePictures, Some(LIVE_SLICE_MS))
        .unwrap();
    assert!(live.is_live());
    assert_eq!(w.next_awake(&mut live), Step::PlanPictures);
    answer(&mut w, &mut live, &Step::PlanPictures);
    let mut fetched = 0;
    loop {
        match w.next_awake(&mut live) {
            Step::Picture { jid, .. } => {
                fetched += 1;
                answer(
                    &mut w,
                    &mut live,
                    &Step::Picture {
                        jid,
                        known_id: None,
                    },
                );
            }
            Step::Stop { stopped } => {
                assert!(matches!(
                    stopped,
                    Stopped::Done | Stopped::Interrupted { .. }
                ));
                break;
            }
            other => panic!("{other:?}"),
        }
    }
    assert!(fetched > 0);
    assert_eq!(w.cp.run, stored, "the stored run is untouched");
    assert_eq!(
        w.cp.negatives, negatives,
        "an empty number list prunes nothing"
    );
    assert!(w.cp.last.is_none());
    assert!(w.cp.pace.picture_next_ms > 0, "the shared pace moved");
    stop(&mut w.cp, Some(&mut live), Interrupt::Cancelled);
    assert_eq!(w.cp.run, stored);
}

#[test]
fn known_id_only_when_the_file_is_on_disk() {
    let mut w = World::new();
    let a = "905550001111@s.whatsapp.net";
    let name = avatars::file_name(a, "7");
    w.book.upsert(
        &[Update {
            jid: a.into(),
            on_whatsapp: Some(true),
            picture_id: Some("7".into()),
            picture_file: Some(name.clone()),
            ..Update::default()
        }],
        NOW - people::REVALIDATE_MS - 1,
    );
    for (files, want) in [
        (vec![], None),
        (
            vec![FileInfo {
                path: format!("/x/{name}"),
                bytes: 10,
                modified_ms: 1,
            }],
            Some("7".to_owned()),
        ),
    ] {
        let mut s = w.begin(&[], Start::LivePictures, None).unwrap();
        assert_eq!(w.next_awake(&mut s), Step::PlanPictures);
        w.report(
            &mut s,
            Report::Files {
                files,
                max_bytes: 1 << 20,
            },
        );
        match w.next_awake(&mut s) {
            Step::Picture { jid, known_id } => {
                assert_eq!(jid, a);
                assert_eq!(known_id, want);
            }
            other => panic!("{other:?}"),
        }
    }
}

#[test]
fn unlink_interrupts_the_stored_run() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(5), Start::Restart, None).unwrap();
    let _ = w.next(&mut s);
    stop(&mut w.cp, None, Interrupt::WaDisconnected);
    assert_eq!(
        w.run().phase,
        Phase::Interrupted {
            reason: Interrupt::WaDisconnected
        }
    );
}

#[test]
fn serde_round_trip_of_the_wire_types() {
    let step = Step::Picture {
        jid: "1@s.whatsapp.net".into(),
        known_id: None,
    };
    let text = serde_json::to_string(&step).unwrap();
    assert_eq!(text, r#"{"kind":"picture","jid":"1@s.whatsapp.net"}"#);
    let stop = Step::Stop {
        stopped: Stopped::Waiting {
            until_ms: 5,
            source: LimitSource::GatewayLegacy,
        },
    };
    assert_eq!(
        serde_json::to_string(&stop).unwrap(),
        r#"{"kind":"stop","stopped":{"outcome":"waiting","until_ms":5,"source":"gateway_legacy"}}"#
    );
    let report: Report = serde_json::from_str(
        r#"{"kind":"picture","jid":"1@s.whatsapp.net","result":{"kind":"stored","picture_id":"9","file":"f.jpg"}}"#,
    )
    .unwrap();
    assert!(matches!(report, Report::Picture { .. }));
    let report: Report =
        serde_json::from_str(r#"{"kind":"rate_limited","source":"whatsapp"}"#).unwrap();
    assert_eq!(
        report,
        Report::RateLimited {
            retry_after_ms: None,
            source: LimitSource::Whatsapp
        }
    );
    let mut w = World::new();
    let _ = w.begin(&numbers(3), Start::Restart, None).unwrap();
    let back: Checkpoint = serde_json::from_slice(&serde_json::to_vec(&w.cp).unwrap()).unwrap();
    assert_eq!(back, w.cp);
}

#[test]
fn jid_of_accepts_plausible_numbers_only() {
    assert_eq!(
        jid_of("+905321234567").as_deref(),
        Some("905321234567@s.whatsapp.net")
    );
    assert_eq!(jid_of("+12345"), None);
    assert_eq!(jid_of("+90 532"), None);
    assert_eq!(jid_of("+1234567890123456"), None);
}
