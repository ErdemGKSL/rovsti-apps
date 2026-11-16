#![allow(clippy::unwrap_used)]

//! People sync v3 end to end: a real server on loopback, the scripted driver, a hand-driven clock, and the real
//! `rosty_wa_client`.

use std::sync::Arc;
use std::time::{Duration, Instant};

use rosty_wa_client::{ClientError, Gateway};
use rosty_wa_proto::{
    AppId, ConnectKey, CreateInstance, ErrorCode, GatewayUrl, LinkRequest, PeopleEntry,
    PeopleJobState, PeopleJobStatus, PeopleRecord, PeopleSyncMode, PeopleSyncStart,
};
use wa_gateway::clock::ManualClock;
use wa_gateway::config::Config;
use wa_gateway::driver::FakeDriver;
use wa_gateway::people_job::JobPace;
use wa_gateway::push::NoopPusher;
use wa_gateway::store::{FetchedProfile, Store};
use wa_gateway::{AppState, serve};

const T0: i64 = 1_790_000_000_000;

struct Server {
    url: GatewayUrl,
    driver: Arc<FakeDriver>,
    clock: ManualClock,
    _dir: tempfile::TempDir,
}

async fn start(auto: bool) -> Server {
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::from_parts(None, &|_| None).unwrap();
    config.data_dir = dir.path().to_owned();
    let store = Store::open(&config.db_path()).unwrap();
    let driver = Arc::new(FakeDriver::new());
    let clock = ManualClock::new(T0, auto);
    let state = AppState::with_parts(
        config,
        store,
        driver.clone(),
        Arc::new(NoopPusher),
        Arc::new(clock.clone()),
        JobPace::default(),
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move { serve(listener, state).await.unwrap() });
    Server {
        url: GatewayUrl::parse(&format!("http://127.0.0.1:{port}")).unwrap(),
        driver,
        clock,
        _dir: dir,
    }
}

async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    tokio::task::spawn_blocking(f).await.unwrap()
}

async fn create(server: &Server) -> Gateway {
    let anonymous = Gateway::new(server.url.clone(), None);
    let (anonymous, created) = blocking(move || {
        let created = anonymous
            .create_instance(&CreateInstance {
                link: LinkRequest::Qr,
                app: AppId::Phone,
            })
            .unwrap();
        (anonymous, created)
    })
    .await;
    anonymous.with_key(ConnectKey::parse(&created.key).unwrap())
}

fn jid(n: u32) -> String {
    format!("1000000{n:05}@s.whatsapp.net")
}

fn entries(range: std::ops::RangeInclusive<u32>) -> Vec<PeopleEntry> {
    range
        .map(|n| PeopleEntry {
            n: format!("+1000000{n:05}"),
            ..Default::default()
        })
        .collect()
}

async fn begin(g: &Gateway, mode: PeopleSyncMode, entries: Vec<PeopleEntry>) -> PeopleJobStatus {
    let g = g.clone();
    blocking(move || g.people_sync_start(&PeopleSyncStart { mode, entries }))
        .await
        .unwrap()
}

async fn watch(
    g: &Gateway,
    job: &str,
    rev: Option<u64>,
    wait: Option<u64>,
) -> Result<PeopleJobStatus, ClientError> {
    let (g, job) = (g.clone(), job.to_owned());
    blocking(move || g.people_sync_status(&job, rev, wait)).await
}

async fn until_ready(g: &Gateway, job: &str) -> PeopleJobStatus {
    for _ in 0..1_000 {
        let status = watch(g, job, None, None).await.unwrap();
        if status.state == PeopleJobState::Ready {
            return status;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    panic!("the job never got ready");
}

type Part = (Vec<(PeopleRecord, Option<Vec<u8>>)>, u32, bool, usize);

async fn part(g: &Gateway, job: &str, after: u32, max: Option<u64>) -> Result<Part, ClientError> {
    let (g, job) = (g.clone(), job.to_owned());
    blocking(move || {
        let mut records = Vec::new();
        let end = g.people_sync_bundle(&job, after, max, |r, p| {
            records.push((r, p));
            Ok(())
        })?;
        let n = records.len();
        Ok((records, end.last_seq, end.more, n))
    })
    .await
}

fn is_code(err: &ClientError, code: ErrorCode) -> bool {
    matches!(err, ClientError::Api(api) if api.code == code)
}

#[tokio::test]
async fn a_job_is_started_joined_downloaded_in_parts_and_dropped() {
    let server = start(true).await;
    let g = create(&server).await;
    server.driver.set_registered((1..=4).map(jid).collect());
    for n in 1..=4 {
        server.driver.set_profile(FetchedProfile {
            jid: jid(n),
            picture_id: Some(format!("p{n}")),
            ..Default::default()
        });
        server
            .driver
            .set_picture(&jid(n), Some((&format!("p{n}"), &[n as u8; 300])));
    }
    let first = begin(&g, PeopleSyncMode::Auto, entries(1..=5)).await;
    assert!(first.mine);
    assert_eq!(first.numbers, 5);
    let ready = until_ready(&g, &first.job_id).await;
    let result = ready.result.unwrap();
    assert_eq!((result.records, result.pictures), (5, 4));
    // The same request joins its job (here: gets the ready one back); nothing is asked again.
    let ops = server.driver.ops().len();
    let again = begin(&g, PeopleSyncMode::Auto, entries(1..=5)).await;
    assert_eq!(again.job_id, first.job_id);
    assert_eq!(server.driver.ops().len(), ops);

    // Parts of about 700 bytes: a record with its 600-byte picture each; resumed by `after`.
    let mut after = 0;
    let mut all = Vec::new();
    loop {
        let (records, last, more, n) = part(&g, &first.job_id, after, Some(700)).await.unwrap();
        assert!(n >= 1);
        all.extend(records);
        after = last;
        if !more {
            break;
        }
    }
    assert_eq!(all.len(), 5);
    assert_eq!(
        all[0].1.as_deref(),
        Some(&[1u8; 600][..]),
        "the full picture"
    );
    assert!(!all[4].0.on_whatsapp && all[4].1.is_none());
    // Done: dropped; then it is gone.
    let (g2, job) = (g.clone(), first.job_id.clone());
    blocking(move || g2.people_sync_cancel(&job)).await.unwrap();
    let err = watch(&g, &first.job_id, None, None).await.unwrap_err();
    assert!(is_code(&err, ErrorCode::NotFound), "{err:?}");

    // The next run with what the device stored: two lines, and no WhatsApp request.
    let known: Vec<PeopleEntry> = all
        .iter()
        .map(|(r, _)| PeopleEntry {
            n: r.number.clone(),
            h: Some(r.hash.clone()),
            p: r.picture_id.clone(),
        })
        .collect();
    let ops = server.driver.ops().len();
    let next = begin(&g, PeopleSyncMode::Auto, known).await;
    let ready = until_ready(&g, &next.job_id).await;
    assert_eq!(ready.result.unwrap().records, 0);
    let (records, last, more, _) = part(&g, &next.job_id, 0, None).await.unwrap();
    assert!(records.is_empty() && last == 0 && !more);
    assert_eq!(server.driver.ops().len(), ops);
}

#[tokio::test]
async fn another_request_while_a_job_works_is_another_devices() {
    let server = start(false).await;
    let g = create(&server).await;
    let first = begin(&g, PeopleSyncMode::Auto, entries(1..=20)).await;
    let other = begin(&g, PeopleSyncMode::Auto, entries(1..=3)).await;
    assert!(!other.mine);
    assert_eq!(other.job_id, first.job_id);
    let joined = begin(&g, PeopleSyncMode::Auto, entries(1..=20)).await;
    assert!(joined.mine);
    // Not ready: no bundle yet.
    let err = part(&g, &first.job_id, 0, None).await.unwrap_err();
    assert!(is_code(&err, ErrorCode::Conflict), "{err:?}");
    // Cancel: it stops; the status says so.
    let (g2, job) = (g.clone(), first.job_id.clone());
    blocking(move || g2.people_sync_cancel(&job)).await.unwrap();
    let status = watch(&g, &first.job_id, None, None).await.unwrap();
    assert_eq!(status.state, PeopleJobState::Cancelled);
    // Now another request starts its own.
    let own = begin(&g, PeopleSyncMode::Auto, entries(1..=3)).await;
    assert!(own.mine);
    assert_ne!(own.job_id, first.job_id);
}

#[tokio::test]
async fn the_long_poll_answers_on_a_change_or_at_its_end() {
    let server = start(false).await;
    let g = create(&server).await;
    let job = begin(&g, PeopleSyncMode::Auto, entries(1..=20)).await;
    // Let the first batch go through: the job then sleeps its gap.
    let mut current = watch(&g, &job.job_id, None, None).await.unwrap();
    for _ in 0..500 {
        if current.done == 15 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
        current = watch(&g, &job.job_id, None, None).await.unwrap();
    }
    assert_eq!(current.done, 15);
    // Nothing changes: the answer comes after the wait, with the same revision.
    let started = Instant::now();
    let same = watch(&g, &job.job_id, Some(current.rev), Some(300))
        .await
        .unwrap();
    assert_eq!(same.rev, current.rev);
    assert!(started.elapsed() >= Duration::from_millis(250));
    // A change wakes it at once.
    let waiting = {
        let (g, id, rev) = (g.clone(), job.job_id.clone(), current.rev);
        tokio::task::spawn_blocking(move || g.people_sync_status(&id, Some(rev), Some(25_000)))
    };
    tokio::time::sleep(Duration::from_millis(100)).await;
    let started = Instant::now();
    server.clock.advance(25_000);
    let changed = waiting.await.unwrap().unwrap();
    assert!(changed.rev > current.rev);
    assert!(started.elapsed() < Duration::from_secs(10));
}

#[tokio::test]
async fn bad_requests_and_unknown_jobs() {
    let server = start(true).await;
    let g = create(&server).await;
    let too_many = entries(1..=5_001);
    let g2 = g.clone();
    let err = blocking(move || {
        g2.people_sync_start(&PeopleSyncStart {
            mode: PeopleSyncMode::Auto,
            entries: too_many,
        })
    })
    .await
    .unwrap_err();
    assert!(is_code(&err, ErrorCode::BadRequest), "{err:?}");
    let err = watch(&g, "0000000000000000", None, None).await.unwrap_err();
    assert!(is_code(&err, ErrorCode::NotFound), "{err:?}");
    let err = part(&g, "0000000000000000", 0, None).await.unwrap_err();
    assert!(is_code(&err, ErrorCode::NotFound), "{err:?}");
    // Without a key: 401.
    let anonymous = Gateway::new(server.url.clone(), None);
    let err = blocking(move || anonymous.people_sync_start(&PeopleSyncStart::default()))
        .await
        .unwrap_err();
    assert_eq!(err, ClientError::Unauthorized);
    // Invalid numbers are skipped, repeats count once.
    let mixed = vec![
        PeopleEntry {
            n: "+100000000001".into(),
            ..Default::default()
        },
        PeopleEntry {
            n: "0532".into(),
            ..Default::default()
        },
        PeopleEntry {
            n: "+100000000001".into(),
            ..Default::default()
        },
    ];
    assert_eq!(begin(&g, PeopleSyncMode::Auto, mixed).await.numbers, 1);
}
