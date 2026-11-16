#![allow(clippy::unwrap_used)]

//! People sync v3 against a throwaway HTTP/1.1 stub on loopback (the pattern of `loopback.rs`).

use std::io::{Read, Write};
use std::net::TcpListener;
use std::thread;

use rosty_wa_client::{ClientError, Gateway};
use rosty_wa_proto::{
    BundleWriter, ConnectKey, ErrorCode, GatewayUrl, PeopleEntry, PeopleJobState, PeopleRecord,
    PeopleSyncMode, PeopleSyncStart,
};

/// One request seen by a stub: the request line and the body.
type Asked = (String, Vec<u8>);

/// Answers one request per entry of `answers` (`(status, content type, body)`), each on its own connection, and
/// returns what was asked.
fn stub(
    answers: Vec<(&'static str, &'static str, Vec<u8>)>,
) -> (GatewayUrl, thread::JoinHandle<Vec<Asked>>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let handle = thread::spawn(move || {
        let mut asked = Vec::new();
        for (status, content_type, body) in answers {
            let (mut stream, _) = listener.accept().unwrap();
            let mut seen = Vec::new();
            let mut buf = [0u8; 1024];
            let head_end = loop {
                if let Some(at) = seen.windows(4).position(|w| w == b"\r\n\r\n") {
                    break at + 4;
                }
                let n = stream.read(&mut buf).unwrap();
                assert!(n > 0, "the request ended before its headers");
                seen.extend_from_slice(&buf[..n]);
            };
            let head = String::from_utf8_lossy(&seen[..head_end]).into_owned();
            let length: usize = head
                .lines()
                .find_map(|line| {
                    let (name, value) = line.split_once(':')?;
                    name.eq_ignore_ascii_case("content-length")
                        .then(|| value.trim().parse().unwrap())
                })
                .unwrap_or(0);
            while seen.len() < head_end + length {
                let n = stream.read(&mut buf).unwrap();
                assert!(n > 0, "the request ended before its body");
                seen.extend_from_slice(&buf[..n]);
            }
            let content_type = if content_type.is_empty() {
                String::new()
            } else {
                format!("Content-Type: {content_type}\r\n")
            };
            let mut response = format!(
                "HTTP/1.1 {status}\r\n{content_type}Content-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            )
            .into_bytes();
            response.extend_from_slice(&body);
            stream.write_all(&response).unwrap();
            let line = head.lines().next().unwrap_or("").to_owned();
            asked.push((line, seen[head_end..].to_vec()));
        }
        asked
    });
    let url = GatewayUrl::parse(&format!("http://127.0.0.1:{port}")).unwrap();
    (url, handle)
}

const JSON: &str = "application/json";
const BUNDLE: &str = "application/x-rosty-people-bundle";

fn key() -> ConnectKey {
    ConnectKey::from_bytes(&[5; 32])
}

const JOB: &str = r#"{"job_id":"5f0c9a1b22d4e7a3","rev":3,"state":"running","phase":"lookup","done":15,"total":40,"numbers":40,"account_epoch":0,"created_ms":1,"updated_ms":2,"expires_ms":3}"#;

#[test]
fn a_people_job_starts_and_is_watched() {
    let (url, asked) = stub(vec![
        ("200 OK", JSON, JOB.into()),
        ("200 OK", JSON, JOB.into()),
    ]);
    let gateway = Gateway::new(url, Some(key()));
    let start = PeopleSyncStart {
        mode: PeopleSyncMode::Full,
        entries: vec![PeopleEntry {
            n: "+100000000001".into(),
            h: Some("00aa".into()),
            p: None,
        }],
    };
    let status = gateway.people_sync_start(&start).unwrap();
    assert_eq!(status.state, PeopleJobState::Running);
    assert!(status.mine);
    let again = gateway
        .people_sync_status("5f0c9a1b22d4e7a3", Some(3), Some(25_000))
        .unwrap();
    assert_eq!(again.rev, 3);
    let asked = asked.join().unwrap();
    assert_eq!(asked[0].0, "POST /v1/people/sync HTTP/1.1");
    assert_eq!(
        String::from_utf8(asked[0].1.clone()).unwrap(),
        r#"{"mode":"full","entries":[{"n":"+100000000001","h":"00aa"}]}"#
    );
    assert_eq!(
        asked[1].0,
        "GET /v1/people/sync/5f0c9a1b22d4e7a3?rev=3&wait_ms=25000 HTTP/1.1"
    );
}

#[test]
fn an_older_gateway_has_no_people_job() {
    let (url, asked) = stub(vec![
        // The router's bare 404: "no such route".
        ("404 Not Found", "", Vec::new()),
        // A job the gateway forgot: its own `not_found`.
        (
            "404 Not Found",
            JSON,
            br#"{"code":"not_found","message":"no such people job"}"#.to_vec(),
        ),
        // Cancelling a job that is gone, or on an old gateway, is fine.
        ("204 No Content", "", Vec::new()),
        ("404 Not Found", "", Vec::new()),
        ("401 Unauthorized", "", Vec::new()),
    ]);
    let gateway = Gateway::new(url, Some(key()));
    let err = gateway
        .people_sync_start(&PeopleSyncStart::default())
        .unwrap_err();
    assert!(err.is_not_found(), "{err:?}");
    let err = gateway.people_sync_status("x", None, None).unwrap_err();
    assert!(err.is_not_found(), "{err:?}");
    gateway.people_sync_cancel("x").unwrap();
    gateway.people_sync_cancel("x").unwrap();
    assert_eq!(
        gateway.people_sync_cancel("x").unwrap_err(),
        ClientError::Unauthorized
    );
    let asked = asked.join().unwrap();
    assert_eq!(asked[1].0, "GET /v1/people/sync/x HTTP/1.1");
    assert_eq!(asked[2].0, "DELETE /v1/people/sync/x HTTP/1.1");
}

fn bundle(after: u32, more: bool) -> Vec<u8> {
    let mut writer = BundleWriter::new("j1", after);
    let record = PeopleRecord {
        seq: after + 1,
        number: "+100000000001".into(),
        hash: "0011223344556677".into(),
        on_whatsapp: true,
        jid: Some("100000000001@s.whatsapp.net".into()),
        picture_id: Some("17".into()),
        ..Default::default()
    };
    writer.record(&record, Some(b"JPEG\nbytes"));
    writer.record(
        &PeopleRecord {
            seq: after + 2,
            number: "+100000000002".into(),
            hash: "8899aabbccddeeff".into(),
            ..Default::default()
        },
        None,
    );
    writer.finish(more)
}

#[test]
fn a_bundle_part_streams_its_records_and_pictures() {
    let (url, asked) = stub(vec![("200 OK", BUNDLE, bundle(4, true))]);
    let mut seen = Vec::new();
    let end = Gateway::new(url, Some(key()))
        .people_sync_bundle("j1", 4, Some(1_000), |record, picture| {
            seen.push((record.seq, picture));
            Ok(())
        })
        .unwrap();
    assert_eq!((end.last_seq, end.more), (6, true));
    assert_eq!(seen, vec![(5, Some(b"JPEG\nbytes".to_vec())), (6, None)]);
    assert_eq!(
        asked.join().unwrap()[0].0,
        "GET /v1/people/sync/j1/bundle?after=4&max_bytes=1000 HTTP/1.1"
    );
}

#[test]
fn a_cut_or_foreign_bundle_part_is_an_error() {
    let full = bundle(0, false);
    // Everything but the end line.
    let end_line = full.windows(6).rposition(|w| w == b"{\"end\"").unwrap();
    let cut = full[..end_line].to_vec();
    let (url, asked) = stub(vec![
        ("200 OK", BUNDLE, cut),
        ("200 OK", BUNDLE, full),
        (
            "409 Conflict",
            JSON,
            br#"{"code":"conflict","message":"the job is not ready"}"#.to_vec(),
        ),
    ]);
    let gateway = Gateway::new(url, Some(key()));
    let mut records = 0;
    let err = gateway
        .people_sync_bundle("j1", 0, None, |_, _| {
            records += 1;
            Ok(())
        })
        .unwrap_err();
    assert!(matches!(err, ClientError::Http(_)), "{err:?}");
    assert_eq!(records, 2, "the records before the cut were handed over");
    // A part for another cursor is refused.
    let err = gateway
        .people_sync_bundle("j1", 9, None, |_, _| Ok(()))
        .unwrap_err();
    assert!(matches!(err, ClientError::Decode(_)), "{err:?}");
    // Not ready yet.
    let err = gateway
        .people_sync_bundle("j1", 0, None, |_, _| Ok(()))
        .unwrap_err();
    assert!(matches!(&err, ClientError::Api(e) if e.code == ErrorCode::Conflict));
    assert_eq!(asked.join().unwrap().len(), 3);
}
