//! The people job against the scripted driver and a hand-driven clock.

use std::sync::Arc;

use rosty_wa_proto::people::{accept_entries, start_digest};
use rosty_wa_proto::{GatewayEvent, InstanceState, PeopleEntry};

use super::*;
use crate::clock::ManualClock;
use crate::config::Config;
use crate::driver::{FakeDriver, WaDriver};
use crate::push::NoopPusher;
use crate::store::{FetchedProfile, NewJob, NewKey, Store};

const T0: i64 = 1_790_000_000_000;

fn jid(n: u32) -> String {
    format!("1000000{n:05}@s.whatsapp.net")
}

fn number(n: u32) -> String {
    format!("+1000000{n:05}")
}

struct Rig {
    state: AppState,
    driver: Arc<FakeDriver>,
    clock: ManualClock,
    instance: InstanceId,
    store: Store,
    config: Config,
    dir: tempfile::TempDir,
}

fn state_of(
    rig_store: &Store,
    config: &Config,
    driver: &Arc<FakeDriver>,
    clock: &ManualClock,
    pace: JobPace,
) -> AppState {
    AppState::with_parts(
        config.clone(),
        rig_store.clone(),
        Arc::clone(driver) as Arc<dyn WaDriver>,
        Arc::new(NoopPusher),
        Arc::new(clock.clone()),
        pace,
    )
}

async fn rig_with(auto: bool, pace: JobPace) -> Rig {
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::from_parts(None, &|_| None).unwrap();
    config.data_dir = dir.path().to_owned();
    let store = Store::open(&config.db_path()).unwrap();
    let driver = Arc::new(FakeDriver::new());
    let clock = ManualClock::new(T0, auto);
    let state = state_of(&store, &config, &driver, &clock, pace);
    let instance = InstanceId::from_bytes(&[7; 12]);
    let key = rosty_wa_proto::ConnectKey::from_bytes(&[7; 32]);
    let new = NewKey {
        key_id: rosty_wa_proto::KeyId::from_bytes(&[7; 8]),
        hash: key.hash(),
        label: "k".into(),
    };
    store
        .create_instance(instance.clone(), new, 1, 4)
        .await
        .unwrap();
    Rig {
        state,
        driver,
        clock,
        instance,
        store,
        config,
        dir,
    }
}

async fn rig(auto: bool) -> Rig {
    rig_with(auto, JobPace::default()).await
}

impl Rig {
    /// "Connects" the instance: the fake driver answers once it runs.
    async fn connect(&self) {
        let sink = self.state.sink(&self.instance);
        self.driver
            .start(self.instance.clone(), None, sink)
            .await
            .unwrap();
    }

    async fn start(&self, mode: PeopleSyncMode, entries: Vec<PeopleEntry>) -> JobRow {
        let accepted = accept_entries(&entries).unwrap();
        let new = NewJob {
            job_id: format!("{:016x}", random()),
            digest: start_digest(mode, &accepted),
            mode,
            entries: accepted,
        };
        let (_, job) = self
            .store
            .people_begin(self.instance.clone(), new, self.state.now())
            .await
            .unwrap();
        ensure(&self.state, &self.instance, &job.job_id);
        job
    }

    async fn job(&self) -> Option<JobRow> {
        self.store
            .people_job(self.instance.clone(), self.state.now())
            .await
            .unwrap()
    }

    /// Waits (real time, at most 10 s) until the job satisfies `done`.
    async fn until(&self, what: &str, done: impl Fn(&JobRow) -> bool) -> JobRow {
        for _ in 0..2_000 {
            if let Some(job) = self.job().await
                && done(&job)
            {
                return job;
            }
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }
        panic!("timed out waiting for {what}: {:?}", self.job().await);
    }

    /// Waits until the job's task sleeps on the clock.
    async fn sleeping(&self) {
        for _ in 0..2_000 {
            if self.clock.sleepers() > 0 {
                return;
            }
            tokio::time::sleep(std::time::Duration::from_millis(5)).await;
        }
        panic!("the job never slept");
    }

    fn ops(&self, kind: &str) -> usize {
        self.driver
            .ops()
            .iter()
            .filter(|op| op.starts_with(kind))
            .count()
    }

    fn whatsapp_ops(&self) -> usize {
        self.ops("lookup") + self.ops("profiles") + self.ops("picture")
    }
}

fn entries(range: std::ops::RangeInclusive<u32>) -> Vec<PeopleEntry> {
    range
        .map(|n| PeopleEntry {
            n: number(n),
            ..Default::default()
        })
        .collect()
}

fn profile(n: u32, picture: Option<&str>) -> FetchedProfile {
    FetchedProfile {
        jid: jid(n),
        picture_id: picture.map(str::to_owned),
        ..Default::default()
    }
}

#[tokio::test]
async fn a_job_reaches_ready_and_an_unchanged_one_asks_whatsapp_nothing() {
    let rig = rig(true).await;
    rig.connect().await;
    let mut events = rig.state.hub().subscribe(&rig.instance, None).events;
    rig.driver.set_registered(vec![jid(1), jid(2)]);
    rig.driver.set_profile(profile(1, Some("p1")));
    rig.driver.set_profile(profile(2, None));
    rig.driver.set_picture(&jid(1), Some(("p1", b"img")));
    rig.store
        .change_profile(
            rig.instance.clone(),
            jid(1),
            ProfileChange::PushName("Ada".into()),
            T0,
        )
        .await
        .unwrap();

    rig.start(PeopleSyncMode::Auto, entries(1..=3)).await;
    let job = rig
        .until("ready", |j| j.state == PeopleJobState::Ready)
        .await;
    assert_eq!(
        (
            rig.ops("lookup 3"),
            rig.ops("profiles 2"),
            rig.ops("picture")
        ),
        (1, 1, 1)
    );
    assert_eq!(job.result.unwrap().records, 3);
    assert_eq!(job.result.unwrap().pictures, 1);
    let planned = plan_records(&rig.state, &rig.instance, &job.job_id, 0)
        .await
        .unwrap();
    let records: Vec<&PeopleRecord> = planned.iter().map(|p| &p.record).collect();
    assert_eq!(records[0].push_name.as_deref(), Some("Ada"));
    assert_eq!(records[0].picture_id.as_deref(), Some("p1"));
    assert!(records[1].on_whatsapp && records[1].picture_id.is_none());
    assert!(!records[2].on_whatsapp && records[2].jid.is_none());
    // The full picture (the fake's preview twice) waits in the spool.
    let file = planned[0].picture.clone().unwrap();
    assert_eq!(std::fs::read(file).unwrap(), b"imgimg");
    // The job wrote straight to the store: no `profile` event went out.
    while let Ok(event) = events.try_recv() {
        assert!(
            !matches!(event.event, GatewayEvent::Profile(_)),
            "{event:?}"
        );
    }

    // The device comes back with the hashes and the picture it stored: nothing to ask, nothing to send.
    let before = rig.whatsapp_ops();
    let again: Vec<PeopleEntry> = records
        .iter()
        .map(|r| PeopleEntry {
            n: r.number.clone(),
            h: Some(r.hash.clone()),
            p: r.picture_id.clone(),
        })
        .collect();
    rig.start(PeopleSyncMode::Auto, again).await;
    let job = rig
        .until("the second job", |j| {
            j.state == PeopleJobState::Ready && j.result.is_some_and(|r| r.records == 0)
        })
        .await;
    assert_eq!(rig.whatsapp_ops(), before, "fresh answers cost no request");
    assert!(
        plan_records(&rig.state, &rig.instance, &job.job_id, 0)
            .await
            .unwrap()
            .is_empty()
    );

    // "Sync Now" asks again.
    rig.start(PeopleSyncMode::Full, entries(1..=3)).await;
    rig.until("the full job", |j| {
        j.state == PeopleJobState::Ready && j.mode == PeopleSyncMode::Full
    })
    .await;
    assert_eq!((rig.ops("lookup"), rig.ops("profiles")), (2, 2));
}

#[tokio::test]
async fn usync_requests_keep_their_gap() {
    let rig = rig(false).await;
    rig.connect().await;
    rig.start(PeopleSyncMode::Auto, entries(1..=20)).await;
    rig.sleeping().await;
    assert_eq!(rig.ops("lookup 15"), 1, "a batch of 15");
    // 20 s ± 20 %: nothing before 16 s.
    rig.clock.advance(15_999);
    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    assert_eq!(rig.ops("lookup"), 1);
    rig.clock.advance(8_002);
    rig.until("ready", |j| j.state == PeopleJobState::Ready)
        .await;
    assert_eq!(rig.ops("lookup 5"), 1);
}

#[tokio::test]
async fn a_429_waits_at_least_the_hint_and_sends_nothing_before() {
    for (hint_s, at_least_ms) in [(Some(120), 120_000), (None, 60_000)] {
        let rig = rig(false).await;
        rig.connect().await;
        rig.driver.fail_next(
            "lookup",
            DriverError::RateLimited {
                retry_after_s: hint_s,
            },
        );
        rig.start(PeopleSyncMode::Auto, entries(1..=2)).await;
        let job = rig
            .until("waiting", |j| j.state == PeopleJobState::Waiting)
            .await;
        let wait = job.wait.unwrap();
        assert_eq!(wait.source, PeopleWaitSource::Whatsapp);
        assert!(wait.until_ms - T0 >= at_least_ms, "{wait:?}");
        assert!(job.status(true).eta_ms.unwrap() >= at_least_ms as u64);
        assert_eq!(rig.ops("lookup"), 1);
        rig.sleeping().await;
        rig.clock
            .advance(u64::try_from(wait.until_ms - T0 - 1).unwrap());
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        assert_eq!(rig.ops("lookup"), 1, "nothing before the wait ends");
        // The interactive routes share the cooldown.
        assert!(rig.state.charge_profiles(&rig.instance, 1).is_err());
        rig.clock.advance(1);
        rig.until("ready", |j| j.state == PeopleJobState::Ready)
            .await;
        assert_eq!(rig.ops("lookup"), 2, "the same batch was asked again");
    }
}

#[tokio::test]
async fn the_gateway_allowance_and_a_disconnected_session_are_waited_out() {
    let pace = JobPace {
        not_connected_retry_ms: 1_000,
        ..JobPace::default()
    };
    let rig = rig_with(false, pace).await;
    // Not connected: the job waits and tries again (a minute in production).
    rig.start(PeopleSyncMode::Auto, entries(1..=2)).await;
    let job = rig
        .until("not connected", |j| {
            j.wait
                .is_some_and(|w| w.source == PeopleWaitSource::NotConnected)
        })
        .await;
    assert_eq!(job.wait.unwrap().until_ms, T0 + 1_000);
    assert_eq!(NOT_CONNECTED_RETRY_MS, 60_000);
    rig.connect().await;
    // Meanwhile an app spent the lookup allowance (60 tokens, one back a second).
    while rig.state.charge_lookups(&rig.instance, 1).is_ok() {}
    rig.sleeping().await;
    rig.clock.advance(1_000);
    let job = rig
        .until("the allowance", |j| {
            j.wait
                .is_some_and(|w| w.source == PeopleWaitSource::Gateway)
        })
        .await;
    // One token is back, the batch needs two: one more second.
    assert_eq!(job.wait.unwrap().until_ms, T0 + 2_000);
    assert_eq!(rig.ops("lookup"), 0);
    rig.sleeping().await;
    rig.clock.advance(1_000);
    rig.until("ready", |j| j.state == PeopleJobState::Ready)
        .await;
    assert_eq!(rig.ops("lookup 2"), 1);
}

#[tokio::test]
async fn a_failed_picture_does_not_fail_the_job() {
    let rig = rig(true).await;
    rig.connect().await;
    rig.driver.set_registered(vec![jid(1)]);
    rig.driver.set_profile(profile(1, Some("p1")));
    rig.driver.set_picture(&jid(1), Some(("p1", b"img")));
    rig.driver
        .fail_next("picture", DriverError::Failed("gone".into()));
    rig.start(PeopleSyncMode::Auto, entries(1..=1)).await;
    let job = rig
        .until("ready", |j| j.state == PeopleJobState::Ready)
        .await;
    let planned = plan_records(&rig.state, &rig.instance, &job.job_id, 0)
        .await
        .unwrap();
    assert!(planned[0].record.picture_missing);
    assert!(planned[0].picture.is_none());
    assert_eq!(job.result.unwrap().pictures, 0);
}

#[tokio::test]
async fn the_spool_budget_leaves_pictures_for_the_next_job() {
    let pace = JobPace {
        spool_max_bytes: 1,
        ..JobPace::default()
    };
    let rig = rig_with(true, pace).await;
    rig.connect().await;
    rig.driver.set_registered(vec![jid(1), jid(2)]);
    for n in 1..=2 {
        rig.driver.set_profile(profile(n, Some("p")));
        rig.driver.set_picture(&jid(n), Some(("p", b"img")));
    }
    rig.start(PeopleSyncMode::Auto, entries(1..=2)).await;
    let job = rig
        .until("ready", |j| j.state == PeopleJobState::Ready)
        .await;
    let planned = plan_records(&rig.state, &rig.instance, &job.job_id, 0)
        .await
        .unwrap();
    assert!(planned[0].picture.is_some());
    assert!(planned[1].record.picture_missing);
    assert_eq!(rig.ops("picture"), 1);
}

#[tokio::test]
async fn cancel_stops_the_job_and_keeps_its_answers() {
    let rig = rig(false).await;
    rig.connect().await;
    let job = rig.start(PeopleSyncMode::Auto, entries(1..=20)).await;
    rig.sleeping().await;
    assert_eq!(rig.ops("lookup"), 1);
    let outcome = cancel(&rig.state, &rig.instance, &job.job_id)
        .await
        .unwrap();
    assert!(matches!(outcome, CancelOutcome::Cancelled(_)));
    rig.clock.advance(600_000);
    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    assert_eq!(rig.ops("lookup"), 1, "nothing more after the cancel");
    assert!(!rig.state.people().any_running(&rig.instance));
    assert_eq!(rig.job().await.unwrap().state, PeopleJobState::Cancelled);
    // "Continue": a new job reuses the 15 answers and asks for the 5 others.
    rig.start(PeopleSyncMode::Auto, entries(1..=20)).await;
    rig.until("ready", |j| j.state == PeopleJobState::Ready)
        .await;
    assert_eq!(rig.ops("lookup"), 2);
    assert_eq!(rig.ops("lookup 5"), 1);
}

#[tokio::test]
async fn a_restart_resumes_from_the_flags() {
    let rig = rig(false).await;
    rig.connect().await;
    let job = rig.start(PeopleSyncMode::Auto, entries(1..=20)).await;
    rig.sleeping().await;
    assert_eq!(rig.ops("lookup 15"), 1);
    // The gateway stops: its tasks are gone, the rows stay.
    rig.state.people().abort_all();
    let restarted = state_of(
        &Store::open(&rig.config.db_path()).unwrap(),
        &rig.config,
        &rig.driver,
        &rig.clock,
        JobPace::default(),
    );
    assert_eq!(resume_all(&restarted).await.unwrap(), 1);
    assert!(restarted.people().is_running(&rig.instance, &job.job_id));
    rig.until("the rest", |j| j.state == PeopleJobState::Ready)
        .await;
    assert_eq!(rig.ops("lookup"), 2);
    assert_eq!(
        rig.ops("lookup 5"),
        1,
        "the first batch was not asked again"
    );
    drop(rig.dir);
}

#[tokio::test]
async fn a_stored_wait_is_slept_out_after_a_restart() {
    let rig = rig(false).await;
    rig.connect().await;
    rig.driver.fail_next(
        "lookup",
        DriverError::RateLimited {
            retry_after_s: Some(300),
        },
    );
    rig.start(PeopleSyncMode::Auto, entries(1..=2)).await;
    rig.until("waiting", |j| j.state == PeopleJobState::Waiting)
        .await;
    rig.state.people().abort_all();
    // A fresh process: no cooldown in memory; the stored wait still holds.
    let restarted = state_of(
        &rig.store,
        &rig.config,
        &rig.driver,
        &rig.clock,
        JobPace::default(),
    );
    resume_all(&restarted).await.unwrap();
    rig.sleeping().await;
    rig.clock.advance(299_000);
    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    assert_eq!(rig.ops("lookup"), 1);
    rig.clock.advance(1_000);
    rig.until("ready", |j| j.state == PeopleJobState::Ready)
        .await;
    assert_eq!(rig.ops("lookup"), 2);
}

#[tokio::test]
async fn another_account_ends_the_task() {
    let rig = rig(false).await;
    rig.connect().await;
    rig.store
        .set_state(
            rig.instance.clone(),
            InstanceState::Connected,
            Some("100000000901@s.whatsapp.net".into()),
            None,
        )
        .await
        .unwrap();
    rig.start(PeopleSyncMode::Auto, entries(1..=20)).await;
    rig.sleeping().await;
    let change = rig
        .store
        .link_account(
            rig.instance.clone(),
            Some("100000000902@s.whatsapp.net".into()),
            None,
        )
        .await
        .unwrap();
    assert!(change.is_some());
    assert!(rig.job().await.is_none(), "the wipe took the job");
    rig.clock.advance(60_000);
    for _ in 0..200 {
        if !rig.state.people().any_running(&rig.instance) {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(5)).await;
    }
    assert!(!rig.state.people().any_running(&rig.instance));
    assert_eq!(rig.ops("lookup"), 1);
}

#[tokio::test]
async fn whatsapp_errors_are_retried_twice_then_fail_the_job() {
    let rig = rig(true).await;
    rig.connect().await;
    for _ in 0..3 {
        rig.driver
            .fail_next("lookup", DriverError::Failed("boom".into()));
    }
    rig.start(PeopleSyncMode::Auto, entries(1..=1)).await;
    let job = rig
        .until("failed", |j| j.state == PeopleJobState::Failed)
        .await;
    assert_eq!(job.error.unwrap().code, job_error::WHATSAPP);
    assert_eq!(rig.ops("lookup"), 3);
}

#[test]
fn the_pacer_slows_on_a_limit_and_recovers_slowly() {
    let mut pacer = Pacer::new(JobPace::default());
    assert_eq!(
        (pacer.batch(), pacer.gap_ms(false), pacer.gap_ms(true)),
        (15, 20_000, 3_000)
    );
    for rand in [0, 20, 40, 41] {
        let gap = pacer.jittered(false, rand);
        assert!((16_000..=24_000).contains(&gap), "{gap}");
    }
    pacer.limited(false);
    assert_eq!((pacer.batch(), pacer.gap_ms(false)), (7, 40_000));
    pacer.limited(false);
    pacer.limited(false);
    assert_eq!((pacer.batch(), pacer.gap_ms(false)), (5, 160_000));
    for _ in 0..10 {
        pacer.limited(false);
    }
    assert_eq!(pacer.gap_ms(false), USYNC_GAP_MAX_MS);
    pacer.limited(true);
    assert_eq!(pacer.gap_ms(true), 6_000);
    // Four successes change nothing; from the fifth the gap comes back 10 % a time.
    for _ in 0..4 {
        pacer.success(false);
    }
    assert_eq!(pacer.gap_ms(false), USYNC_GAP_MAX_MS);
    pacer.success(false);
    assert_eq!(pacer.gap_ms(false), 270_000);
    for _ in 0..200 {
        pacer.success(false);
    }
    assert_eq!((pacer.batch(), pacer.gap_ms(false)), (15, 20_000));
}

#[test]
fn records_go_out_when_the_hash_or_the_picture_differs() {
    let lookup = LookupRow {
        on_whatsapp: true,
        business: false,
        verified_name: Some("Shop".into()),
        checked_ms: T0,
    };
    let profile = WaProfile {
        jid: jid(1),
        push_name: Some(" Ada ".into()),
        picture_id: Some("p1".into()),
        lid: Some("77@lid".into()),
        ..Default::default()
    };
    let mut number = NumberRow {
        seq: 1,
        jid: jid(1),
        have_hash: None,
        have_picture: None,
        looked: true,
        profiled: true,
        pictured: PICTURED_OK,
        spooled_id: None,
    };
    assert!(
        record_for(&number, None, Some(&profile)).is_none(),
        "no answer"
    );
    let (record, picture) = record_for(&number, Some(&lookup), Some(&profile)).unwrap();
    assert!(picture);
    assert_eq!(record.push_name.as_deref(), Some("Ada"));
    assert_eq!(record.verified_name.as_deref(), Some("Shop"));
    assert_eq!(record.lid.as_deref(), Some("77@lid"));
    assert_eq!(record.hash, people_hash(&record.hash_input()));
    // Same hash, same picture: nothing.
    number.have_hash = Some(record.hash.clone());
    number.have_picture = Some("p1".into());
    assert!(record_for(&number, Some(&lookup), Some(&profile)).is_none());
    // Same hash, the device lost its file: the record and its bytes.
    number.have_picture = None;
    assert_eq!(
        record_for(&number, Some(&lookup), Some(&profile)).map(|(_, p)| p),
        Some(true)
    );
    // A new name: the record without bytes.
    number.have_picture = Some("p1".into());
    let renamed = WaProfile {
        push_name: Some("Ada L".into()),
        ..profile.clone()
    };
    assert_eq!(
        record_for(&number, Some(&lookup), Some(&renamed)).map(|(_, p)| p),
        Some(false)
    );
    // A negative is a record too, without a JID.
    let negative = LookupRow {
        on_whatsapp: false,
        ..lookup.clone()
    };
    let (record, picture) = record_for(&number, Some(&negative), Some(&profile)).unwrap();
    assert!(!picture && record.jid.is_none() && record.verified_name.is_none());
}

#[test]
fn lookups_follow_the_freshness_rules() {
    let now = T0;
    let row = |on, age: i64| LookupRow {
        on_whatsapp: on,
        business: false,
        verified_name: None,
        checked_ms: now - age,
    };
    const DAY: i64 = 24 * 3_600_000;
    assert!(needs_lookup(PeopleSyncMode::Auto, None, now));
    assert!(!needs_lookup(
        PeopleSyncMode::Auto,
        Some(&row(false, 6 * DAY)),
        now
    ));
    assert!(needs_lookup(
        PeopleSyncMode::Auto,
        Some(&row(false, 7 * DAY)),
        now
    ));
    assert!(!needs_lookup(
        PeopleSyncMode::Auto,
        Some(&row(true, 29 * DAY)),
        now
    ));
    assert!(needs_lookup(
        PeopleSyncMode::Auto,
        Some(&row(true, 30 * DAY)),
        now
    ));
    assert!(needs_lookup(PeopleSyncMode::Full, Some(&row(true, 0)), now));
    // A clock that went back is not fresh.
    assert!(needs_lookup(
        PeopleSyncMode::Auto,
        Some(&row(true, -1)),
        now
    ));
}

#[tokio::test]
async fn the_manual_clock_drives_the_state() {
    let rig = rig(false).await;
    assert_eq!(rig.state.now(), T0);
    rig.clock.advance(5);
    assert_eq!(rig.state.clock().now_ms(), T0 + 5);
}
