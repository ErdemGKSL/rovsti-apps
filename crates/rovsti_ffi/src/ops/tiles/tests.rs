use super::*;
use crate::envelope::decode;
use crate::tile_buffer::{self, FLAG_DARK};
use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::atomic::AtomicUsize;

const FIXTURE: &[u8] =
    include_bytes!("../../../../rovsti_geo/src/tiles/testdata/osm-14-9520-6130.mvt");

fn temp(name: &str) -> PathBuf {
    static N: AtomicUsize = AtomicUsize::new(0);
    let dir = std::env::temp_dir().join(format!(
        "rovsti-tiles-{name}-{}-{}",
        std::process::id(),
        N.fetch_add(1, Ordering::Relaxed)
    ));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

/// A localhost tile server that answers every path with `body`, after `delay`.
struct Stub {
    template: String,
    hits: Arc<AtomicUsize>,
}

fn stub(body: &'static [u8], delay: Duration) -> Stub {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let hits = Arc::new(AtomicUsize::new(0));
    let counter = Arc::clone(&hits);
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut stream) = stream else { continue };
            let counter = Arc::clone(&counter);
            std::thread::spawn(move || {
                let mut head = Vec::new();
                let mut byte = [0u8; 1];
                while !head.ends_with(b"\r\n\r\n") {
                    if stream.read(&mut byte).unwrap_or(0) == 0 {
                        return;
                    }
                    head.push(byte[0]);
                }
                counter.fetch_add(1, Ordering::SeqCst);
                std::thread::sleep(delay);
                let _ = write!(
                    stream,
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                let _ = stream.write_all(body);
            });
        }
    });
    Stub {
        template: format!("http://127.0.0.1:{port}/{{z}}/{{x}}/{{y}}"),
        hits,
    }
}

fn open_at(url: Option<&str>, dir: &std::path::Path, cap: Option<u64>) -> i64 {
    open(OpenReq {
        server_url: url.map(str::to_owned),
        cache_cap: cap,
        dir: Some(dir.to_str().unwrap().to_owned()),
    })
    .unwrap()
    .handle
}

fn seed(dir: &std::path::Path, source: &TileSource, id: TileId, bytes: &[u8]) {
    let file = dir
        .join(source.key())
        .join(id.z.to_string())
        .join(id.x.to_string())
        .join(format!("{}.mvt", id.y));
    std::fs::create_dir_all(file.parent().unwrap()).unwrap();
    std::fs::write(file, bytes).unwrap();
}

fn fixture_id() -> TileId {
    TileId::new(14, 9520, 6130).unwrap()
}

fn error_of(bytes: &[u8]) -> FfiError {
    assert!(!tile_buffer::is_buffer(bytes), "an envelope, not a buffer");
    match decode(bytes).unwrap() {
        Answer::Err(error) => error,
        other => panic!("{other:?}"),
    }
}

fn wait_finished(events: &Mutex<Vec<Value>>) -> Vec<Value> {
    let until = Instant::now() + Duration::from_secs(30);
    loop {
        let seen = events.lock().unwrap().clone();
        if seen.last().is_some_and(|e| e["finished"] == true) {
            return seen;
        }
        assert!(
            Instant::now() < until,
            "the download did not finish: {seen:?}"
        );
        std::thread::sleep(Duration::from_millis(10));
    }
}

fn collector() -> (Emit, Arc<Mutex<Vec<Value>>>) {
    let seen = Arc::new(Mutex::new(Vec::new()));
    let sink = Arc::clone(&seen);
    let emit: Emit = Arc::new(move |v| sink.lock().unwrap().push(v.clone()));
    (emit, seen)
}

fn area(zooms: (u8, u8), size: f64) -> OfflineReq {
    OfflineReq {
        handle: 0,
        circle: None,
        bbox: Some(Bbox {
            south: 41.0,
            west: 28.96,
            north: 41.0 + size,
            east: 28.96 + size,
        }),
        zooms: Zooms {
            min: Some(zooms.0),
            max: Some(zooms.1),
        },
    }
}

#[test]
fn a_cache_only_tile_comes_from_the_seeded_cache_without_a_network() {
    let root = temp("seeded");
    let source = TileSource::default_source();
    seed(&root, &source, fixture_id(), FIXTURE);
    let handle = open_at(None, &root, None);
    let bytes = tile_export(handle, 14, 9520, 6130, FLAG_CACHE_ONLY as i32);
    assert!(tile_buffer::is_buffer(&bytes));
    let tile = tile_buffer::decode(&bytes).unwrap();
    let geometry = TileGeometry::from_bytes(fixture_id(), FIXTURE).unwrap();
    let drawn: usize = tile
        .classes
        .iter()
        .filter(|c| !c.is_casing())
        .map(|c| c.points())
        .sum();
    assert_eq!(drawn, geometry.vertices);
    assert_eq!(tile.flags, FLAG_CACHE_ONLY);

    let dark = tile_buffer::decode(&tile_export(
        handle,
        14,
        9520,
        6130,
        (FLAG_CACHE_ONLY | FLAG_DARK) as i32,
    ))
    .unwrap();
    assert_ne!(dark.background, tile.background);

    let miss = error_of(&tile_export(handle, 14, 9520, 6131, FLAG_CACHE_ONLY as i32));
    assert_eq!(miss.kind, ErrorKind::NotFound);
    assert_eq!(miss.detail.unwrap()["reason"], "not_cached");
    assert_eq!(
        error_of(&tile_export(handle, 14, -1, 0, 0)).kind,
        ErrorKind::Invalid
    );
    assert_eq!(
        error_of(&tile_export(handle, 99, 0, 0, 0)).kind,
        ErrorKind::Invalid
    );
    assert_eq!(
        error_of(&tile_export(handle, 14, 1 << 20, 0, 0)).kind,
        ErrorKind::Invalid
    );
    assert!(handles::global().release(handle));
    assert_eq!(
        error_of(&tile_export(handle, 14, 9520, 6130, 0)).kind,
        ErrorKind::NotFound
    );
    let _ = std::fs::remove_dir_all(root);
}

#[test]
fn a_miss_is_fetched_once_and_then_served_from_the_cache() {
    let server = stub(FIXTURE, Duration::ZERO);
    let root = temp("fetch");
    let handle = open_at(Some(&server.template), &root, None);
    let first = tile_export(handle, 14, 9520, 6130, 0);
    assert!(tile_buffer::is_buffer(&first));
    assert_eq!(server.hits.load(Ordering::SeqCst), 1);
    assert_eq!(tile_export(handle, 14, 9520, 6130, 0), first);
    assert_eq!(
        server.hits.load(Ordering::SeqCst),
        1,
        "second read is cached"
    );
    assert!(tile_buffer::is_buffer(&tile_export(
        handle,
        14,
        9520,
        6130,
        FLAG_CACHE_ONLY as i32
    )));
    let used = usage(HandleReq { handle }).unwrap();
    assert_eq!(
        (used.source_tiles, used.source_bytes),
        (1, FIXTURE.len() as u64)
    );
    assert!(used.total_bytes >= FIXTURE.len() as u64);
    handles::global().release(handle);
    let _ = std::fs::remove_dir_all(root);
}

#[test]
fn failures_are_envelopes_with_a_kind_and_nothing_broken_is_cached() {
    let root = temp("errors");
    let garbage = stub(b"not a vector tile", Duration::ZERO);
    let handle = open_at(Some(&garbage.template), &root, None);
    let error = error_of(&tile_export(handle, 5, 1, 1, 0));
    assert_eq!(error.kind, ErrorKind::Parse);
    assert_eq!(usage(HandleReq { handle }).unwrap().source_tiles, 0);
    handles::global().release(handle);

    let dead = open_at(Some("http://127.0.0.1:1/{z}/{x}/{y}"), &root, None);
    let error = error_of(&tile_export(dead, 5, 1, 1, 0));
    assert_eq!(error.kind, ErrorKind::Network);
    assert_eq!(error.detail.unwrap()["reason"], "unreachable");
    handles::global().release(dead);
    let _ = std::fs::remove_dir_all(root);
}

#[test]
fn open_refuses_a_bad_url_with_a_reason() {
    let error = open(OpenReq {
        server_url: Some("https://a.b/{z}/{x}".into()),
        cache_cap: None,
        dir: Some(temp("badurl").to_str().unwrap().into()),
    })
    .err()
    .unwrap();
    assert_eq!(error.kind, ErrorKind::Invalid);
    assert_eq!(error.detail.unwrap()["reason"], "placeholder");
}

#[test]
fn test_server_reads_layers_and_names_the_failure() {
    let good = stub(FIXTURE, Duration::ZERO);
    let ok = test_server(TestReq {
        server_url: good.template.clone(),
    })
    .unwrap();
    assert!(ok.layers >= 1);
    let bad = stub(b"junk", Duration::ZERO);
    let error = test_server(TestReq {
        server_url: bad.template.clone(),
    })
    .err()
    .unwrap();
    assert_eq!(error.kind, ErrorKind::Parse);
    let dead = test_server(TestReq {
        server_url: "http://127.0.0.1:1/{z}/{x}/{y}".into(),
    })
    .err()
    .unwrap();
    assert_eq!(dead.kind, ErrorKind::Network);
    let url = test_server(TestReq {
        server_url: "ftp://x/{z}/{x}/{y}".into(),
    })
    .err()
    .unwrap();
    assert_eq!(url.detail.unwrap()["reason"], "scheme");
}

#[test]
fn usage_clear_except_clear_all_and_set_cap() {
    let root = temp("cache-ops");
    let mine = TileSource::default_source();
    let other = TileSource::parse("https://tiles.example.org/{z}/{x}/{y}").unwrap();
    for n in 0..3 {
        seed(
            &root,
            &mine,
            TileId::new(14, 9520 + n, 6130).unwrap(),
            FIXTURE,
        );
    }
    seed(&root, &other, fixture_id(), FIXTURE);
    let handle = open_at(None, &root, None);

    let sources = usage_by_source(HandleReq { handle }).unwrap().sources;
    assert_eq!(sources.len(), 2);
    assert_eq!(sources.iter().filter(|s| s.current).count(), 1);
    assert_eq!(sources.iter().find(|s| s.current).unwrap().key, mine.key());
    assert!(sources.iter().all(|s| s.bytes >= FIXTURE.len() as u64));
    let used = usage(HandleReq { handle }).unwrap();
    assert_eq!(used.source_tiles, 3);

    let smaller = set_cap(SetCapReq {
        handle,
        cap_bytes: FIXTURE.len() as u64 + 10,
    })
    .unwrap();
    assert_eq!(
        smaller.source_tiles, 1,
        "the least recently used tiles went"
    );
    assert_eq!(smaller.cap_bytes, FIXTURE.len() as u64 + 10);

    let freed = clear_except(ClearExceptReq {
        handle,
        keep_key: None,
    })
    .unwrap()
    .freed_bytes;
    assert!(freed >= FIXTURE.len() as u64);
    assert_eq!(
        usage_by_source(HandleReq { handle }).unwrap().sources.len(),
        1
    );
    assert_eq!(
        usage(HandleReq { handle }).unwrap().source_tiles,
        1,
        "the open cache is untouched"
    );

    let cleared = clear_all(HandleReq { handle }).unwrap().freed_bytes;
    assert!(cleared > 0);
    let after = usage(HandleReq { handle }).unwrap();
    assert_eq!((after.total_bytes, after.source_tiles), (0, 0));
    assert_eq!(
        error_of(&tile_export(handle, 14, 9520, 6130, FLAG_CACHE_ONLY as i32)).kind,
        ErrorKind::NotFound
    );
    handles::global().release(handle);
    let _ = std::fs::remove_dir_all(root);
}

#[test]
fn stand_in_prefers_the_tile_then_the_nearest_ancestor() {
    let t = |z, x, y| TileId::new(z, x, y).unwrap();
    let want = t(14, 9520, 6130);
    let ask = |have: Vec<TileId>| stand_in_op(StandInReq { want, have }).unwrap().tile;
    assert_eq!(ask(vec![want, t(13, 4760, 3065)]), Some(want));
    assert_eq!(
        ask(vec![t(10, 595, 383), t(12, 2380, 1532)]),
        Some(t(12, 2380, 1532))
    );
    assert_eq!(ask(vec![t(8, 148, 95)]), None);
}

#[test]
fn encode_mvt_builds_the_same_buffer_the_tile_export_would() {
    let request = |mvt: &[u8], flags| EncodeReq {
        mvt: STANDARD.encode(mvt),
        z: 14,
        x: 9520,
        y: 6130,
        flags,
    };
    let buffer = STANDARD
        .decode(encode_mvt(request(FIXTURE, 1)).unwrap().buffer)
        .unwrap();
    let tile = tile_buffer::decode(&buffer).unwrap();
    assert_eq!(tile.flags, FLAG_DARK);
    let direct = tile_buffer::encode(&TileGeometry::from_bytes(fixture_id(), FIXTURE).unwrap(), 1);
    assert_eq!(buffer, direct);
    assert_eq!(
        encode_mvt(request(b"nope", 0)).err().unwrap().kind,
        ErrorKind::Parse
    );
    let outside = EncodeReq {
        mvt: String::new(),
        z: 3,
        x: 8,
        y: 0,
        flags: 0,
    };
    assert_eq!(encode_mvt(outside).err().unwrap().kind, ErrorKind::Invalid);
}

#[test]
fn an_offline_area_reports_progress_and_ends_with_every_tile_cached() {
    let server = stub(FIXTURE, Duration::ZERO);
    let root = temp("offline");
    let handle = open_at(Some(&server.template), &root, None);
    let (emit, seen) = collector();
    let started = start_job(
        OfflineReq {
            handle,
            ..area((10, 12), 0.1)
        },
        emit,
    )
    .unwrap();
    assert!(started.total > 4 && !started.capped);
    assert_eq!(started.estimate_bytes, estimate_bytes(started.total));
    let events = wait_finished(&seen);
    let last = events.last().unwrap();
    assert_eq!(last["total"], started.total);
    assert_eq!(last["done"], started.total);
    assert_eq!(last["saved"], started.total);
    assert_eq!(last["failed"], 0);
    assert_eq!(last["fraction"], 1.0);
    assert_eq!(last["cancelled"], false);
    assert_eq!(last["job"], started.handle);
    assert!(
        last["text"]
            .as_str()
            .unwrap()
            .starts_with("Map area ready offline.")
    );
    let mut before = 0;
    for e in &events {
        let done = e["done"].as_u64().unwrap();
        assert!(done >= before, "progress never goes back");
        before = done;
        assert!((0.0..=1.0).contains(&e["fraction"].as_f64().unwrap()));
    }
    assert!(
        events
            .iter()
            .filter(|e| e["finished"] == false)
            .all(|e| e["text"].as_str().unwrap().starts_with("Downloading "))
    );
    assert_eq!(server.hits.load(Ordering::SeqCst), started.total);
    assert_eq!(
        usage(HandleReq { handle }).unwrap().source_tiles,
        started.total
    );

    // The same area again finds everything cached: no request reaches the server.
    let (emit, seen) = collector();
    let again = start_job(
        OfflineReq {
            handle,
            ..area((10, 12), 0.1)
        },
        emit,
    )
    .unwrap();
    let events = wait_finished(&seen);
    assert_eq!(events.last().unwrap()["saved"], again.total);
    assert_eq!(server.hits.load(Ordering::SeqCst), started.total);
    handles::global().release(started.handle);
    handles::global().release(again.handle);
    handles::global().release(handle);
    let _ = std::fs::remove_dir_all(root);
}

#[test]
fn cancelling_an_offline_area_stops_it_and_keeps_what_was_saved() {
    let server = stub(FIXTURE, Duration::from_millis(120));
    let root = temp("cancel");
    let handle = open_at(Some(&server.template), &root, None);
    let (emit, seen) = collector();
    let started = start_job(
        OfflineReq {
            handle,
            ..area((11, 12), 0.5)
        },
        emit,
    )
    .unwrap();
    assert!(started.total > 20);
    std::thread::sleep(Duration::from_millis(250));
    let stopped = offline_cancel(CancelReq {
        job: started.handle,
    })
    .unwrap();
    assert_eq!(stopped.total, started.total);
    let events = wait_finished(&seen);
    let last = events.last().unwrap();
    assert_eq!(last["cancelled"], true);
    assert!(last["done"].as_u64().unwrap() < started.total as u64);
    assert!(
        last["text"]
            .as_str()
            .unwrap()
            .starts_with("Download cancelled.")
    );
    assert!(
        server.hits.load(Ordering::SeqCst) < started.total,
        "the queue was dropped"
    );
    let kept = usage(HandleReq { handle }).unwrap().source_tiles;
    assert_eq!(
        kept as u64,
        last["saved"].as_u64().unwrap(),
        "saved tiles stay in the cache"
    );
    assert!(kept > 0);
    handles::global().release(started.handle);
    assert_eq!(
        offline_cancel(CancelReq {
            job: started.handle
        })
        .err()
        .unwrap()
        .kind,
        ErrorKind::NotFound
    );
    handles::global().release(handle);
    let _ = std::fs::remove_dir_all(root);
}

#[test]
fn releasing_the_job_handle_cancels_the_download() {
    let server = stub(FIXTURE, Duration::from_millis(100));
    let root = temp("drop");
    let handle = open_at(Some(&server.template), &root, None);
    let (emit, seen) = collector();
    let started = start_job(
        OfflineReq {
            handle,
            ..area((11, 12), 0.5)
        },
        emit,
    )
    .unwrap();
    std::thread::sleep(Duration::from_millis(150));
    handles::global().release(started.handle);
    let events = wait_finished(&seen);
    assert!(events.last().unwrap()["cancelled"] == true);
    handles::global().release(handle);
    let _ = std::fs::remove_dir_all(root);
}

#[test]
fn a_bad_area_is_refused_before_anything_starts() {
    let root = temp("bad-area");
    let handle = open_at(None, &root, None);
    let emit = || -> Emit { Arc::new(|_| {}) };
    let mut flipped = area((9, 11), 0.1);
    flipped.handle = handle;
    flipped.bbox.as_mut().unwrap().south = 42.0;
    assert_eq!(
        start_job(flipped, emit()).err().unwrap().detail.unwrap()["reason"],
        "bbox"
    );
    let mut backwards = area((12, 9), 0.1);
    backwards.handle = handle;
    assert_eq!(
        start_job(backwards, emit()).err().unwrap().detail.unwrap()["reason"],
        "zooms"
    );
    let mut nan = area((9, 11), 0.1);
    nan.handle = handle;
    nan.bbox.as_mut().unwrap().west = f64::NAN;
    assert_eq!(
        start_job(nan, emit()).err().unwrap().kind,
        ErrorKind::Invalid
    );
    let mut lost = area((9, 11), 0.1);
    lost.handle = 12345;
    assert_eq!(
        start_job(lost, emit()).err().unwrap().kind,
        ErrorKind::NotFound
    );
    handles::global().release(handle);
    let _ = std::fs::remove_dir_all(root);
}

#[test]
fn a_circle_around_a_crossing_downloads_its_tiles() {
    let server = stub(FIXTURE, Duration::ZERO);
    let root = temp("circle");
    let handle = open_at(Some(&server.template), &root, None);
    let (emit, seen) = collector();
    let req = OfflineReq {
        handle,
        bbox: None,
        circle: Some(Circle {
            center: Coord::new(41.0, 28.994),
            radius_m: 600.0,
            margin_m: Some(300.0),
            budget: Some(40),
        }),
        zooms: Zooms {
            min: Some(10),
            max: Some(12),
        },
    };
    let started = start_job(req, emit).unwrap();
    assert!((3..=40).contains(&started.total));
    assert_eq!(started.top_zoom, 12);
    let events = wait_finished(&seen);
    assert_eq!(events.last().unwrap()["saved"], started.total);
    let both = OfflineReq {
        handle,
        circle: None,
        bbox: None,
        zooms: Zooms::default(),
    };
    assert_eq!(
        start_job(both, Arc::new(|_| {}))
            .err()
            .unwrap()
            .detail
            .unwrap()["reason"],
        "area"
    );
    handles::global().release(started.handle);
    handles::global().release(handle);
    let _ = std::fs::remove_dir_all(root);
}
