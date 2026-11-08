//! AC3 benchmark: a keystroke on the contact search must stay under a frame.
//!
//! 5 000 seeded synthetic contacts (Latin, Turkish and Cyrillic names, one or two numbers each) go through
//! `ops::call` exactly as Kotlin sends them: request bytes in, envelope bytes out, JSON parsed on both sides, so
//! the numbers include serialization. The debug run is a smoke test (median under 25 ms); the release run
//! (`cargo test --release -p rovsti_ffi --test search_bench -- --ignored --nocapture`) holds the real budget:
//! p95 under 5 ms for `search.query`, `search.query_loose` and `t9.query`.

#![allow(clippy::unwrap_used, clippy::expect_used)]

use rovsti_ffi::ops::call;
use serde_json::{Value, json};
use std::time::{Duration, Instant};

/// xorshift64*: a seeded generator without a dependency, so every run sees the same contacts.
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    fn pick<'a>(&mut self, list: &[&'a str]) -> &'a str {
        list[(self.next() % list.len() as u64) as usize]
    }
}

const LATIN: [&str; 12] = [
    "Anna", "Michael", "Sophie", "Lukas", "Emma", "Hans", "Clara", "Jonas", "Marie", "Felix",
    "Nora", "Paul",
];
const LATIN_LAST: [&str; 10] = [
    "Schmidt", "Müller", "Weber", "Fischer", "Meyer", "Wagner", "Becker", "Hoffmann", "Koch",
    "Richter",
];
const TURKISH: [&str; 14] = [
    "Ayşe", "Mehmet", "Zeynep", "Mustafa", "Fatma", "İbrahim", "Elif", "Hüseyin", "Şeyma",
    "Çağlar", "Gökhan", "Ömer", "Büşra", "Ümit",
];
const TURKISH_LAST: [&str; 12] = [
    "Yılmaz", "Kaya", "Demir", "Çelik", "Şahin", "Yıldız", "Öztürk", "Aydın", "Özdemir", "Arslan",
    "Doğan", "Kılıç",
];
const CYRILLIC: [&str; 10] = [
    "Борис",
    "Анна",
    "Иван",
    "Мария",
    "Дмитрий",
    "Елена",
    "Сергей",
    "Ольга",
    "Андрей",
    "Наталья",
];
const CYRILLIC_LAST: [&str; 8] = [
    "Иванов",
    "Петрова",
    "Сидоров",
    "Смирнова",
    "Кузнецов",
    "Попова",
    "Волков",
    "Соколова",
];

fn number(rng: &mut Rng) -> String {
    let body = rng.next() % 10_000_000;
    match rng.next() % 4 {
        0 => format!(
            "05{:02} {:03} {:02} {:02}",
            30 + rng.next() % 30,
            body / 10_000,
            body / 100 % 100,
            body % 100
        ),
        1 => format!(
            "+90 5{:02} {:03} {:04}",
            30 + rng.next() % 30,
            body / 10_000,
            body % 10_000
        ),
        2 => format!("+49 151 {:07}", body),
        _ => format!(
            "+7 495 {:03}-{:02}-{:02}",
            body / 10_000,
            body / 100 % 100,
            body % 100
        ),
    }
}

fn contacts(count: usize) -> Value {
    let mut rng = Rng(0x9E37_79B9_7F4A_7C15);
    let list: Vec<Value> = (0..count)
        .map(|i| {
            let name = match rng.next() % 3 {
                0 => format!("{} {}", rng.pick(&LATIN), rng.pick(&LATIN_LAST)),
                1 => format!("{} {}", rng.pick(&TURKISH), rng.pick(&TURKISH_LAST)),
                _ => format!("{} {}", rng.pick(&CYRILLIC), rng.pick(&CYRILLIC_LAST)),
            };
            let numbers: Vec<Value> = (0..1 + rng.next() % 2)
                .map(|_| json!({ "raw": number(&mut rng) }))
                .collect();
            json!({ "id": i as i64 + 1, "display_name": name, "numbers": numbers, "starred": i % 97 == 0 })
        })
        .collect();
    Value::Array(list)
}

/// 20 queries of 1 to 6 characters: name starts, Turkish and Cyrillic text, number fragments.
const QUERIES: [&str; 20] = [
    "a",
    "an",
    "ann",
    "schm",
    "müll",
    "ay",
    "ayş",
    "çel",
    "boris",
    "иван",
    "сидор",
    "5",
    "05",
    "0532",
    "+9053",
    "495",
    "151 2",
    "zey",
    "yılmaz",
    "mehme",
];
/// Digit queries of 1 to 6 keys for the T9 strip.
const DIGITS: [&str; 20] = [
    "2", "26", "266", "7246", "6855", "29", "297", "235", "26747", "4826", "74367", "5", "05",
    "0532", "90532", "495", "1512", "939", "945563", "63463",
];

fn run(op: &str, request: Value) -> (Value, Duration) {
    let bytes = request.to_string();
    let started = Instant::now();
    let answer = call(op, bytes.as_bytes());
    let parsed: Value = serde_json::from_slice(&answer).expect("envelope json");
    let elapsed = started.elapsed();
    assert!(parsed.get("ok").is_some(), "{op}: {parsed}");
    (parsed["ok"].clone(), elapsed)
}

struct Bench {
    handle: i64,
}

impl Bench {
    fn new() -> Self {
        let (ok, _) = run("search.index_new", json!({ "contacts": contacts(5_000) }));
        assert_eq!(ok["count"], 5_000);
        Self {
            handle: ok["handle"].as_i64().expect("handle"),
        }
    }

    /// Sample times of `op` over every query, `rounds` times.
    fn samples(&self, op: &str, queries: &[&str], rounds: usize) -> Vec<Duration> {
        let mut out = Vec::new();
        for _ in 0..rounds {
            for q in queries {
                let request = if op == "t9.query" {
                    json!({ "handle": self.handle, "digits": q })
                } else {
                    json!({ "handle": self.handle, "query": q, "limit": 50 })
                };
                out.push(run(op, request).1);
            }
        }
        out.sort();
        out
    }
}

fn percentile(sorted: &[Duration], p: f64) -> Duration {
    sorted[((sorted.len() as f64 * p).ceil() as usize).clamp(1, sorted.len()) - 1]
}

#[test]
fn queries_find_what_they_should() {
    let bench = Bench::new();
    let (ok, _) = run(
        "search.query",
        json!({ "handle": bench.handle, "query": "ayş", "limit": 5 }),
    );
    assert!(
        ok["total"].as_u64().unwrap() > 50,
        "Turkish names are found: {ok}"
    );
    assert_eq!(ok["rows"].as_array().unwrap().len(), 5);
    let (ok, _) = run(
        "search.query",
        json!({ "handle": bench.handle, "query": "иван", "limit": 3 }),
    );
    assert!(
        ok["total"].as_u64().unwrap() > 50,
        "Cyrillic names are found"
    );
    let (ok, _) = run(
        "t9.query",
        json!({ "handle": bench.handle, "digits": "26" }),
    );
    assert!(!ok["hits"].as_array().unwrap().is_empty(), "T9 finds names");
}

#[test]
fn debug_median_is_under_25_ms() {
    let bench = Bench::new();
    for (op, queries) in [
        ("search.query", &QUERIES),
        ("search.query_loose", &QUERIES),
        ("t9.query", &DIGITS),
    ] {
        let samples = bench.samples(op, queries, 2);
        let median = percentile(&samples, 0.5);
        assert!(
            median < Duration::from_millis(25),
            "{op}: median {median:?}"
        );
    }
}

#[test]
#[ignore = "release budget: cargo test --release -p rovsti_ffi --test search_bench -- --ignored --nocapture"]
fn release_p95_is_under_5_ms() {
    let bench = Bench::new();
    for (op, queries) in [
        ("search.query", &QUERIES),
        ("search.query_loose", &QUERIES),
        ("t9.query", &DIGITS),
    ] {
        let samples = bench.samples(op, queries, 25);
        let (p50, p95, max) = (
            percentile(&samples, 0.5),
            percentile(&samples, 0.95),
            *samples.last().unwrap(),
        );
        println!(
            "{op}: n={} p50={p50:?} p95={p95:?} max={max:?}",
            samples.len()
        );
        assert!(p95 < Duration::from_millis(5), "{op}: p95 {p95:?}");
    }
}
