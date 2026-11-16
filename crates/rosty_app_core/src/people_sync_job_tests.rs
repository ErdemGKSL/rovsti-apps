//! Unit tests of the planner's job mode (people sync v3): a fake clock, no network.

use rosty_wa_proto::people::{PeopleJobError, PeopleJobResult, PeopleWait};

use super::*;
use crate::people::{FileInfo, Update};
use crate::wa_link::avatars;

const NOW: i64 = 1_790_000_000_000;
const JOB: &str = "5f0c9a1b22d4e7a3";

fn link() -> LinkId {
    LinkId::new("inst-1", "https://rosty.erdemdev.tr/")
}

/// Fake numbers `+1000000000NN`.
fn number(n: u32) -> String {
    format!("+1000000000{n:02}")
}

fn jid(n: u32) -> String {
    jid_of(&number(n)).unwrap()
}

fn numbers(range: std::ops::RangeInclusive<u32>) -> Vec<String> {
    range.map(number).collect()
}

fn status(job: &str, state: PeopleJobState, rev: u64) -> PeopleJobStatus {
    PeopleJobStatus {
        job_id: job.into(),
        rev,
        mine: true,
        state,
        phase: if state == PeopleJobState::Ready {
            PeopleJobPhase::Bundle
        } else {
            PeopleJobPhase::Lookup
        },
        done: 0,
        total: 3,
        numbers: 3,
        wait: None,
        eta_ms: Some(20_000),
        result: (state == PeopleJobState::Ready).then_some(PeopleJobResult {
            records: 3,
            pictures: 1,
            bytes: 1_000,
        }),
        error: None,
        account_epoch: 0,
        created_ms: NOW,
        updated_ms: NOW,
        expires_ms: Some(NOW + 3_600_000),
    }
}

fn record(n: u32, on: bool, picture: Option<&str>) -> JobRecord {
    JobRecord {
        seq: n,
        number: number(n),
        hash: format!("{n:016x}"),
        on_whatsapp: on,
        jid: on.then(|| jid(n)),
        push_name: on.then(|| format!("P{n}")),
        picture_id: picture.map(str::to_owned),
        ..JobRecord::default()
    }
}

fn stored(mut r: JobRecord) -> JobRecord {
    let id = r.picture_id.clone().unwrap();
    r.picture = Some(JobPicture::Stored {
        file: avatars::file_name(&jid(r.seq), &id),
    });
    r
}

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

    fn begin(&mut self, nums: &[String], start: Start, slice: Option<i64>) -> Session {
        self.try_begin(nums, start, slice).unwrap()
    }

    fn try_begin(
        &mut self,
        nums: &[String],
        start: Start,
        slice: Option<i64>,
    ) -> Result<Session, Refused> {
        begin_with(
            &mut self.cp,
            &self.book,
            link(),
            nums,
            start,
            slice,
            self.now,
            true,
        )
    }

    fn next(&mut self, s: &mut Session) -> Step {
        next(&mut self.cp, s, &self.book, self.now, 20)
    }

    fn report(&mut self, s: &mut Session, r: Report) -> Committed {
        report(&mut self.cp, s, &mut self.book, self.now, 20, r)
    }

    /// The picture files on disk, as the caller lists them.
    fn files(&mut self, s: &mut Session, names: &[String]) -> Committed {
        let files = names
            .iter()
            .map(|name| FileInfo {
                path: format!("/files/people/{name}"),
                bytes: 100,
                modified_ms: 1,
            })
            .collect();
        self.report(
            s,
            Report::Files {
                files,
                max_bytes: 1 << 24,
            },
        )
    }

    fn run(&self) -> &Run {
        self.cp.run.as_ref().unwrap()
    }
}

fn start_step(step: &Step) -> &[PeopleEntry] {
    match step {
        Step::JobStart { entries, .. } => entries,
        other => panic!("not a start: {other:?}"),
    }
}

/// A whole first run of three numbers: 1 on WhatsApp with a picture, 2 not, 3 on WhatsApp without one.
fn first_run(w: &mut World) {
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    assert_eq!(w.next(&mut s), Step::PlanPictures);
    w.files(&mut s, &[]);
    let step = w.next(&mut s);
    assert_eq!(
        start_step(&step),
        &[
            PeopleEntry {
                n: number(1),
                ..Default::default()
            },
            PeopleEntry {
                n: number(2),
                ..Default::default()
            },
            PeopleEntry {
                n: number(3),
                ..Default::default()
            },
        ]
    );
    assert!(matches!(
        step,
        Step::JobStart {
            mode: PeopleSyncMode::Auto,
            ..
        }
    ));
    let c = w.report(
        &mut s,
        Report::JobStarted {
            status: status(JOB, PeopleJobState::Running, 1),
        },
    );
    assert_eq!(c.stage, Some(Stage::Server));
    assert_eq!(c.job.as_ref().unwrap().job_id, JOB);
    assert_eq!(
        w.next(&mut s),
        Step::JobWatch {
            job_id: JOB.into(),
            rev: 1,
            wait_ms: JOB_WATCH_MS,
        }
    );
    w.report(
        &mut s,
        Report::Job {
            status: status(JOB, PeopleJobState::Ready, 5),
        },
    );
    assert_eq!(
        w.next(&mut s),
        Step::JobFetch {
            job_id: JOB.into(),
            after: 0,
            max_bytes: BUNDLE_MAX_BYTES,
        }
    );
    let c = w.report(
        &mut s,
        Report::Bundle {
            job_id: JOB.into(),
            records: vec![
                stored(record(1, true, Some("p1"))),
                record(2, false, None),
                record(3, true, None),
            ],
            last_seq: 3,
            more: false,
            bytes: 900,
        },
    );
    assert!(c.book_dirty);
    assert_eq!(c.changed, 2);
    assert_eq!(c.stage, Some(Stage::Download));
    assert_eq!(c.progress, Progress { done: 3, total: 3 });
    assert_eq!(w.next(&mut s), Step::JobDone { job_id: JOB.into() });
    w.report(&mut s, Report::JobClosed);
    assert_eq!(
        w.next(&mut s),
        Step::Stop {
            stopped: Stopped::Done
        }
    );
}

#[test]
fn a_first_run_starts_watches_downloads_and_closes_the_job() {
    let mut w = World::new();
    first_run(&mut w);
    assert!(w.cp.run.is_none());
    let last = w.cp.last.unwrap();
    assert_eq!((last.changed, last.bytes, last.people), (Some(2), 900, 2));
    let ada = w.book.lookup(&jid(1)).unwrap();
    assert_eq!(ada.push_name.as_deref(), Some("P1"));
    assert_eq!(ada.picture_id.as_deref(), Some("p1"));
    assert!(ada.picture_file.is_some() && !ada.picture_stale);
    assert!(w.book.lookup(&jid(2)).is_none(), "a negative adds nobody");
    assert_eq!(w.cp.remote.synced.len(), 3);
    assert_eq!(w.cp.remote.support, Some(true));
    assert_eq!(w.cp.remote.account_epoch, Some(0));
    assert!(!w.cp.remote.synced[&jid(2)].on_whatsapp);
}

#[test]
fn an_unchanged_run_commits_nothing() {
    let mut w = World::new();
    first_run(&mut w);
    let book = w.book.clone();
    let synced = w.cp.remote.synced.clone();
    w.now += 12 * 3_600_000;
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    assert_eq!(w.next(&mut s), Step::PlanPictures);
    let file = w
        .book
        .lookup(&jid(1))
        .unwrap()
        .picture_file
        .clone()
        .unwrap();
    let c = w.files(&mut s, &[file]);
    assert!(!c.book_dirty && c.drop_files.is_empty());
    let step = w.next(&mut s);
    let entries = start_step(&step);
    // Every number with its hash, and the picture held on disk.
    assert!(entries.iter().all(|e| e.h.is_some()));
    assert_eq!(entries[0].p.as_deref(), Some("p1"));
    assert_eq!((entries[1].p.as_ref(), entries[2].p.as_ref()), (None, None));
    let mut commits = vec![w.report(
        &mut s,
        Report::JobStarted {
            status: status("job2", PeopleJobState::Ready, 3),
        },
    )];
    assert!(matches!(w.next(&mut s), Step::JobFetch { after: 0, .. }));
    commits.push(w.report(
        &mut s,
        Report::Bundle {
            job_id: "job2".into(),
            records: vec![],
            last_seq: 0,
            more: false,
            bytes: 100,
        },
    ));
    assert!(matches!(w.next(&mut s), Step::JobDone { .. }));
    commits.push(w.report(&mut s, Report::JobClosed));
    assert_eq!(
        w.next(&mut s),
        Step::Stop {
            stopped: Stopped::Done
        }
    );
    assert!(
        commits
            .iter()
            .all(|c| !c.book_dirty && c.changed == 0 && c.drop_files.is_empty())
    );
    assert_eq!(w.book, book, "people.json is not touched");
    assert_eq!(w.cp.remote.synced, synced);
    assert_eq!(w.cp.last.unwrap().changed, Some(0));
}

#[test]
fn changed_records_update_the_book_and_their_hash() {
    let mut w = World::new();
    first_run(&mut w);
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[]);
    w.next(&mut s);
    w.report(
        &mut s,
        Report::JobStarted {
            status: status("job2", PeopleJobState::Ready, 3),
        },
    );
    w.next(&mut s);
    let mut renamed = record(3, true, Some("p3"));
    renamed.hash = "ffff000000000003".into();
    renamed.push_name = Some("New".into());
    renamed.picture = Some(JobPicture::Failed);
    let mut gone = record(1, false, None);
    gone.hash = "ffff000000000001".into();
    let c = w.report(
        &mut s,
        Report::Bundle {
            job_id: "job2".into(),
            records: vec![gone, renamed],
            last_seq: 3,
            more: false,
            bytes: 200,
        },
    );
    assert!(c.book_dirty);
    assert_eq!(c.changed, 2);
    // Not on WhatsApp any more: kept, marked.
    assert!(!w.book.lookup(&jid(1)).unwrap().on_whatsapp);
    // A picture that could not be stored is stale: the next run asks again for its bytes.
    let p3 = w.book.lookup(&jid(3)).unwrap();
    assert_eq!(p3.push_name.as_deref(), Some("New"));
    assert!(p3.picture_stale);
    assert_eq!(w.cp.remote.synced[&jid(3)].hash, "ffff000000000003");
    assert!(!w.cp.remote.synced[&jid(1)].on_whatsapp);
}

#[test]
fn a_lost_person_or_file_drops_its_hash_or_picture() {
    let mut w = World::new();
    first_run(&mut w);
    // The book lost number 3; number 1's file is not on disk.
    w.book.people.retain(|p| p.jid != jid(3));
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[]);
    let step = w.next(&mut s);
    let entries = start_step(&step);
    assert!(entries[0].h.is_some() && entries[0].p.is_none());
    assert!(
        entries[1].h.is_some(),
        "a negative needs nobody in the book"
    );
    assert!(entries[2].h.is_none());
    // A stale picture is not offered either.
    w.book
        .people
        .iter_mut()
        .for_each(|p| p.picture_stale = true);
    let file = w
        .book
        .lookup(&jid(1))
        .unwrap()
        .picture_file
        .clone()
        .unwrap();
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[file]);
    assert!(start_step(&w.next(&mut s))[0].p.is_none());
}

#[test]
fn a_watch_resumes_by_job_id_after_process_death() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[]);
    w.next(&mut s);
    w.report(
        &mut s,
        Report::JobStarted {
            status: status(JOB, PeopleJobState::Running, 4),
        },
    );
    assert!(after_process_start(&mut w.cp));
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    // No listing, no start: the stored job is watched.
    assert_eq!(
        w.next(&mut s),
        Step::JobWatch {
            job_id: JOB.into(),
            rev: 4,
            wait_ms: JOB_WATCH_MS,
        }
    );
    let status = status(JOB, PeopleJobState::Waiting, 5);
    let status = PeopleJobStatus {
        wait: Some(PeopleWait {
            until_ms: NOW + 60_000,
            source: PeopleWaitSource::Whatsapp,
        }),
        done: 15,
        total: 40,
        ..status
    };
    let c = w.report(&mut s, Report::Job { status });
    let view = c.job.unwrap();
    assert_eq!(
        (view.wait_until_ms, view.wait_source),
        (Some(NOW + 60_000), Some(PeopleWaitSource::Whatsapp))
    );
    assert_eq!(
        c.progress,
        Progress {
            done: 15,
            total: 40
        }
    );
    assert_eq!(
        w.next(&mut s),
        Step::JobWatch {
            job_id: JOB.into(),
            rev: 5,
            wait_ms: JOB_WATCH_MS,
        }
    );
}

#[test]
fn the_slice_ends_while_the_server_works() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(1..=3), Start::Auto, Some(60_000));
    w.next(&mut s);
    w.files(&mut s, &[]);
    w.next(&mut s);
    w.report(
        &mut s,
        Report::JobStarted {
            status: status(JOB, PeopleJobState::Running, 1),
        },
    );
    w.now += 50_000;
    assert_eq!(
        w.next(&mut s),
        Step::JobWatch {
            job_id: JOB.into(),
            rev: 1,
            wait_ms: 10_000,
        }
    );
    w.report(
        &mut s,
        Report::Job {
            status: status(JOB, PeopleJobState::Running, 1),
        },
    );
    w.now += 10_000;
    assert_eq!(
        w.next(&mut s),
        Step::Stop {
            stopped: Stopped::Interrupted {
                reason: Interrupt::ServerWorking
            }
        }
    );
    let st = status_of(&w);
    let run = st.run.unwrap();
    assert!(run.resumable);
    assert_eq!(run.job.unwrap().job_id, JOB);
}

fn status_of(w: &World) -> Status {
    super::status(&w.cp, w.now)
}

#[test]
fn a_cut_download_keeps_the_cursor() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[]);
    w.next(&mut s);
    w.report(
        &mut s,
        Report::JobStarted {
            status: status(JOB, PeopleJobState::Ready, 2),
        },
    );
    w.next(&mut s);
    w.report(
        &mut s,
        Report::Bundle {
            job_id: JOB.into(),
            records: vec![stored(record(1, true, Some("p1")))],
            last_seq: 1,
            more: true,
            bytes: 100,
        },
    );
    assert!(matches!(w.next(&mut s), Step::JobFetch { after: 1, .. }));
    w.report(
        &mut s,
        Report::Error {
            error: ErrorKind::Network,
            message: "cut".into(),
        },
    );
    assert_eq!(
        w.next(&mut s),
        Step::Stop {
            stopped: Stopped::Interrupted {
                reason: Interrupt::Network
            }
        }
    );
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    assert!(matches!(w.next(&mut s), Step::JobFetch { after: 1, .. }));
}

#[test]
fn a_lost_job_starts_again_and_three_losses_fail() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[]);
    for round in 0..3 {
        assert!(
            matches!(w.next(&mut s), Step::JobStart { .. }),
            "round {round}"
        );
        w.report(
            &mut s,
            Report::JobStarted {
                status: status(JOB, PeopleJobState::Running, 1),
            },
        );
        assert!(matches!(w.next(&mut s), Step::JobWatch { .. }));
        w.report(&mut s, Report::JobGone);
    }
    assert!(matches!(
        w.next(&mut s),
        Step::Stop {
            stopped: Stopped::Failed {
                reason: FailReason::Gateway,
                ..
            }
        }
    ));
}

#[test]
fn another_devices_job_is_watched_until_it_ends() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[]);
    w.next(&mut s);
    let other = PeopleJobStatus {
        mine: false,
        ..status("other", PeopleJobState::Running, 7)
    };
    let c = w.report(&mut s, Report::JobStarted { status: other });
    assert!(!c.job.unwrap().mine);
    assert_eq!(
        w.next(&mut s),
        Step::JobWatch {
            job_id: "other".into(),
            rev: 7,
            wait_ms: JOB_WATCH_MS,
        }
    );
    let mut over = status("other", PeopleJobState::Ready, 8);
    over.mine = false;
    w.report(&mut s, Report::Job { status: over });
    // Its bundle is not ours: our own job starts.
    assert!(matches!(w.next(&mut s), Step::JobStart { .. }));
}

#[test]
fn an_old_gateway_runs_the_v2_steps_and_is_asked_again_after_a_day() {
    let mut w = World::new();
    let nums = numbers(1..=3);
    let mut s = w.begin(&nums, Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[]);
    w.next(&mut s);
    assert!(
        s.queue.is_some(),
        "the start listed the files and planned the pictures"
    );
    let c = w.report(&mut s, Report::JobUnsupported);
    assert_eq!(c.stage, Some(Stage::Lookup));
    // That plan predates the lookups and profiles to come: the picture stage plans again from their answers.
    assert!(s.queue.is_none());
    // The v2 steps, as a v2 run would ask them.
    let mut legacy = Checkpoint::default();
    let mut legacy_session = begin(
        &mut legacy,
        &w.book,
        link(),
        &nums,
        Start::Auto,
        None,
        w.now,
    )
    .unwrap();
    let expected = next(&mut legacy, &mut legacy_session, &w.book, w.now, 20);
    assert_eq!(w.next(&mut s), expected);
    assert!(matches!(expected, Step::Lookup { .. }));
    // A 429 there is honoured with its hint, as in v2.
    w.report(
        &mut s,
        Report::RateLimited {
            retry_after_ms: Some(120_000),
            source: LimitSource::Whatsapp,
        },
    );
    assert!(w.cp.pace.not_before_ms >= w.now + 120_000);
    // Within a day the next run goes straight to the v2 steps; after it, the job routes are asked again.
    w.cp.run = None;
    w.cp.pace = Pace::default();
    w.now += 3_600_000;
    let mut s = w.begin(&nums, Start::Auto, None);
    assert!(matches!(w.next(&mut s), Step::Lookup { .. }));
    w.cp.run = None;
    w.now += REMOTE_RETRY_MS;
    let mut s = w.begin(&nums, Start::Auto, None);
    assert_eq!(w.next(&mut s), Step::PlanPictures);
}

#[test]
fn begin_without_jobs_is_the_v2_sync() {
    let mut w = World::new();
    let nums = numbers(1..=3);
    let mut s = begin(&mut w.cp, &w.book, link(), &nums, Start::Auto, None, w.now).unwrap();
    assert!(matches!(w.next(&mut s), Step::Lookup { .. }));
    // A stored job-mode run goes on in the v2 steps there.
    let mut w = World::new();
    let mut s = w.begin(&nums, Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[]);
    w.next(&mut s);
    w.report(
        &mut s,
        Report::JobStarted {
            status: status(JOB, PeopleJobState::Running, 1),
        },
    );
    stop(&mut w.cp, Some(&mut s), Interrupt::SliceBudget);
    let mut s = begin(&mut w.cp, &w.book, link(), &nums, Start::Auto, None, w.now).unwrap();
    assert_eq!(w.run().stage, Stage::Lookup);
    assert!(matches!(w.next(&mut s), Step::Lookup { .. }));
}

#[test]
fn stop_closes_the_job_and_continue_starts_a_new_one() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[]);
    w.next(&mut s);
    w.report(
        &mut s,
        Report::JobStarted {
            status: status(JOB, PeopleJobState::Running, 1),
        },
    );
    // The user's Stop: the app cancels the job and reports it.
    w.report(&mut s, Report::Cancelled);
    let st = status_of(&w);
    assert_eq!(
        st.run.unwrap().job.unwrap().job_id,
        JOB,
        "the app can name the job to cancel"
    );
    // "Continue": the stopped job is closed (again; harmless), then a new one starts.
    let mut s = w.begin(&numbers(1..=3), Start::Resume, None);
    assert_eq!(w.next(&mut s), Step::JobDone { job_id: JOB.into() });
    w.report(&mut s, Report::JobClosed);
    assert_eq!(w.next(&mut s), Step::PlanPictures);
    w.files(&mut s, &[]);
    assert!(matches!(
        w.next(&mut s),
        Step::JobStart {
            mode: PeopleSyncMode::Auto,
            ..
        }
    ));
}

#[test]
fn sync_now_closes_a_running_job_and_asks_for_everything() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[]);
    w.next(&mut s);
    w.report(
        &mut s,
        Report::JobStarted {
            status: status(JOB, PeopleJobState::Running, 1),
        },
    );
    let mut s = w.begin(&numbers(1..=3), Start::Restart, None);
    assert_eq!(w.next(&mut s), Step::JobDone { job_id: JOB.into() });
    w.report(&mut s, Report::JobClosed);
    w.next(&mut s);
    w.files(&mut s, &[]);
    assert!(matches!(
        w.next(&mut s),
        Step::JobStart {
            mode: PeopleSyncMode::Full,
            ..
        }
    ));
}

#[test]
fn a_failed_job_fails_the_run_and_the_next_one_starts_fresh() {
    let mut w = World::new();
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[]);
    w.next(&mut s);
    w.report(
        &mut s,
        Report::JobStarted {
            status: status(JOB, PeopleJobState::Running, 1),
        },
    );
    w.next(&mut s);
    let failed = PeopleJobStatus {
        error: Some(PeopleJobError {
            code: "whatsapp".into(),
            message: "boom".into(),
        }),
        ..status(JOB, PeopleJobState::Failed, 2)
    };
    w.report(&mut s, Report::Job { status: failed });
    assert_eq!(
        w.next(&mut s),
        Step::Stop {
            stopped: Stopped::Failed {
                reason: FailReason::Gateway,
                message: "boom".into()
            }
        }
    );
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    assert_eq!(w.next(&mut s), Step::PlanPictures);
}

#[test]
fn another_link_or_account_clears_the_hashes() {
    let mut w = World::new();
    first_run(&mut w);
    assert_eq!(w.cp.remote.synced.len(), 3);
    // Another account on the same link: the hashes go, the book stays, our job is closed and started again.
    let mut s = w.begin(&numbers(1..=3), Start::Auto, None);
    w.next(&mut s);
    w.files(&mut s, &[]);
    w.next(&mut s);
    let newer = PeopleJobStatus {
        account_epoch: 1,
        ..status("job2", PeopleJobState::Running, 1)
    };
    w.report(&mut s, Report::JobStarted { status: newer });
    assert!(w.cp.remote.synced.is_empty());
    assert_eq!(w.cp.remote.account_epoch, Some(1));
    assert_eq!(w.book.people.len(), 2);
    assert_eq!(
        w.next(&mut s),
        Step::JobDone {
            job_id: "job2".into()
        }
    );
    w.report(&mut s, Report::JobClosed);
    assert!(start_step(&w.next(&mut s)).iter().all(|e| e.h.is_none()));
    // Another link: everything goes.
    let other = LinkId::new("inst-2", "https://rosty.erdemdev.tr/");
    w.cp.remote.support = Some(false);
    begin_with(
        &mut w.cp,
        &w.book,
        other,
        &numbers(1..=3),
        Start::Auto,
        None,
        w.now,
        true,
    )
    .unwrap();
    assert_eq!(w.cp.remote.support, None);
    assert!(w.cp.remote.synced.is_empty());
}

#[test]
fn removed_numbers_leave_the_hashes_and_attempts_are_recorded() {
    let mut w = World::new();
    first_run(&mut w);
    w.now += 1_000;
    let _s = w.begin(&numbers(1..=2), Start::Auto, None);
    assert_eq!(w.cp.remote.synced.len(), 2);
    assert_eq!(status_of(&w).attempt_ms, Some(w.now));
}

#[test]
fn the_checkpoint_of_v2_reads_and_round_trips() {
    // A v2 file: no `remote`, no job fields.
    let v2 = r#"{"schema_version":1,"run":{"id":1,"mode":"full","phase":{"kind":"running"},"stage":"lookup"},"last":{"at_ms":1,"people":2,"downloaded":1,"duration_ms":5}}"#;
    let cp: Checkpoint = serde_json::from_str(v2).unwrap();
    assert_eq!(cp.remote, Remote::default());
    assert!(cp.run.as_ref().unwrap().job.is_none());
    assert_eq!(cp.last.unwrap().changed, None);
    let mut w = World::new();
    first_run(&mut w);
    let text = serde_json::to_string(&w.cp).unwrap();
    assert_eq!(serde_json::from_str::<Checkpoint>(&text).unwrap(), w.cp);
}

#[test]
fn job_steps_and_reports_are_pinned() {
    let pinned = |step: Step, text: &str| {
        assert_eq!(serde_json::to_string(&step).unwrap(), text);
        assert_eq!(serde_json::from_str::<Step>(text).unwrap(), step);
    };
    pinned(
        Step::JobStart {
            mode: PeopleSyncMode::Auto,
            entries: vec![PeopleEntry {
                n: number(1),
                h: Some("00".into()),
                p: None,
            }],
        },
        r#"{"kind":"job_start","mode":"auto","entries":[{"n":"+100000000001","h":"00"}]}"#,
    );
    pinned(
        Step::JobWatch {
            job_id: JOB.into(),
            rev: 3,
            wait_ms: 25_000,
        },
        r#"{"kind":"job_watch","job_id":"5f0c9a1b22d4e7a3","rev":3,"wait_ms":25000}"#,
    );
    pinned(
        Step::JobFetch {
            job_id: JOB.into(),
            after: 2,
            max_bytes: 8,
        },
        r#"{"kind":"job_fetch","job_id":"5f0c9a1b22d4e7a3","after":2,"max_bytes":8}"#,
    );
    pinned(
        Step::JobDone { job_id: JOB.into() },
        r#"{"kind":"job_done","job_id":"5f0c9a1b22d4e7a3"}"#,
    );
    let report: Report = serde_json::from_str(
        r#"{"kind":"bundle","job_id":"j","last_seq":2,"records":[{"seq":1,"number":"+100000000001","hash":"00","on_whatsapp":true,"picture_id":"1","picture":{"kind":"stored","file":"f.jpg"}},{"seq":2,"number":"+100000000002","hash":"01","on_whatsapp":false}]}"#,
    )
    .unwrap();
    match report {
        Report::Bundle {
            records,
            more,
            bytes,
            ..
        } => {
            assert_eq!((records.len(), more, bytes), (2, false, 0));
            assert_eq!(
                records[0].picture,
                Some(JobPicture::Stored {
                    file: "f.jpg".into()
                })
            );
        }
        other => panic!("{other:?}"),
    }
    for (text, expected) in [
        (r#"{"kind":"job_unsupported"}"#, Report::JobUnsupported),
        (r#"{"kind":"job_gone"}"#, Report::JobGone),
        (r#"{"kind":"job_closed"}"#, Report::JobClosed),
    ] {
        assert_eq!(serde_json::from_str::<Report>(text).unwrap(), expected);
    }
    let _ = Update::default();
}
