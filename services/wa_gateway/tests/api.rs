#![allow(clippy::unwrap_used)]

//! End to end: a real server on loopback with the scripted driver, driven by
//! the real `rovsti_wa_client`.

use std::sync::Arc;
use std::time::Duration;

use futures::channel::mpsc::UnboundedReceiver;
use futures::{SinkExt, StreamExt};
use rovsti_wa_client::{ClientError, ClientEvent, EventStream, Gateway, call_audio, subscribe};
use rovsti_wa_proto::{
    AppId, CallPhase, ChatState, ConnectKey, CreateInstance, CreatedInstance, EndReason, ErrorCode,
    EventEnvelope, GatewayEvent, GatewayUrl, GroupInfo, InstanceState, LinkRequest, MarkRead,
    MediaKind, MessageBody, Participant, ScheduleText, ScheduledState, ScheduledText, SendReaction,
    SendText, TypingState,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use wa_gateway::config::Config;
use wa_gateway::driver::DriverError;
use wa_gateway::driver::FakeDriver;
use wa_gateway::store::{InstanceId, Store};
use wa_gateway::{AppState, serve};

struct Server {
    url: GatewayUrl,
    driver: Arc<FakeDriver>,
    state: AppState,
    dir: tempfile::TempDir,
}

async fn start(max_instances: u32) -> Server {
    start_with(
        max_instances,
        Config::from_parts(None, &|_| None).unwrap().max_media_bytes,
    )
    .await
}

async fn start_with(max_instances: u32, max_media_bytes: usize) -> Server {
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::from_parts(None, &|_| None).unwrap();
    config.data_dir = dir.path().to_owned();
    config.max_instances = max_instances;
    config.max_media_bytes = max_media_bytes;
    let store = Store::open(&config.db_path()).unwrap();
    let driver = Arc::new(FakeDriver::new());
    let state = AppState::new(config, store, driver.clone());
    let served = state.clone();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        serve(listener, served).await.unwrap();
    });
    Server {
        url: GatewayUrl::parse(&format!("http://127.0.0.1:{port}")).unwrap(),
        driver,
        state,
        dir,
    }
}

/// The client is blocking; keep it off the runtime threads.
async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    tokio::task::spawn_blocking(f).await.unwrap()
}

async fn create(server: &Server) -> (Gateway, CreatedInstance) {
    let anonymous = Gateway::new(server.url.clone(), None);
    let created = blocking(move || {
        anonymous
            .create_instance(&CreateInstance {
                link: LinkRequest::Qr,
                app: AppId::Phone,
            })
            .map(|c| (anonymous, c))
    })
    .await
    .unwrap();
    let (anonymous, created) = created;
    let key = ConnectKey::parse(&created.key).unwrap();
    (anonymous.with_key(key), created)
}

struct Events {
    _stream: EventStream,
    rx: UnboundedReceiver<ClientEvent>,
}

fn watch(server: &Server, created: &CreatedInstance, after: Option<u64>) -> Events {
    let key = ConnectKey::parse(&created.key).unwrap();
    let (stream, rx) = subscribe(server.url.clone(), key, after).unwrap();
    Events {
        _stream: stream,
        rx,
    }
}

impl Events {
    async fn next(&mut self) -> ClientEvent {
        tokio::time::timeout(Duration::from_secs(10), self.rx.next())
            .await
            .expect("timed out waiting for an event")
            .expect("event stream ended")
    }

    /// The next gateway event, skipping connection bookkeeping.
    async fn next_event(&mut self) -> EventEnvelope {
        loop {
            if let ClientEvent::Event(e) = self.next().await {
                return e;
            }
        }
    }
}

#[tokio::test]
async fn create_then_qr_then_connected() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    assert_eq!(created.instance_id.len(), 24);

    let mut events = watch(&server, &created, Some(0));
    assert_eq!(events.next().await, ClientEvent::Connected);
    let first = events.next_event().await;
    assert_eq!(first.seq, 1);
    match first.event {
        GatewayEvent::Qr { payload, .. } => assert!(payload.starts_with("fake-qr:")),
        other => panic!("expected a Qr, got {other:?}"),
    }
    let second = events.next_event().await;
    assert_eq!(second.seq, 2);
    match second.event {
        GatewayEvent::State(status) => {
            assert_eq!(status.state, InstanceState::Connected);
            assert_eq!(status.instance_id, created.instance_id);
        }
        other => panic!("expected State, got {other:?}"),
    }

    let status = blocking(move || gateway.status()).await.unwrap();
    assert_eq!(status.state, InstanceState::Connected);
    assert_eq!(status.push_name.as_deref(), Some("Fake"));
    assert_eq!(server.driver.started().len(), 1);
}

#[tokio::test]
async fn phone_number_linking_gives_a_pair_code() {
    let server = start(4).await;
    let anonymous = Gateway::new(server.url.clone(), None);
    let created = blocking(move || {
        anonymous.create_instance(&CreateInstance {
            link: LinkRequest::PhoneNumber {
                number: "+90 532 123 45 67".into(),
            },
            app: AppId::Messages,
        })
    })
    .await
    .unwrap();
    let mut events = watch(&server, &created, Some(0));
    assert!(matches!(
        events.next_event().await.event,
        GatewayEvent::PairCode { .. }
    ));

    // A number without a country code is refused up front.
    let anonymous = Gateway::new(server.url.clone(), None);
    let err = blocking(move || {
        anonymous.create_instance(&CreateInstance {
            link: LinkRequest::PhoneNumber {
                number: "0532 123 45 67".into(),
            },
            app: AppId::Messages,
        })
    })
    .await
    .unwrap_err();
    assert!(matches!(err, ClientError::Api(e) if e.code == ErrorCode::BadRequest));
}

#[tokio::test]
async fn a_second_key_sees_the_same_instance() {
    let server = start(4).await;
    let (first, created) = create(&server).await;

    let first2 = first.clone();
    let issued = blocking(move || first2.mint_key("Messages app"))
        .await
        .unwrap();
    assert_eq!(issued.label, "Messages app");
    assert_ne!(issued.key, created.key);

    let second = first.with_key(ConnectKey::parse(&issued.key).unwrap());
    let (a, b) = (first.clone(), second.clone());
    let status_a = blocking(move || a.status()).await.unwrap();
    let status_b = blocking(move || b.status()).await.unwrap();
    assert_eq!(status_a, status_b);
    assert_eq!(status_b.instance_id, created.instance_id);

    let (s2, f2) = (second.clone(), first.clone());
    let keys = blocking(move || s2.keys()).await.unwrap();
    assert_eq!(keys.len(), 2);
    let current: Vec<_> = keys.iter().filter(|k| k.current).collect();
    assert_eq!(current.len(), 1);
    assert_eq!(current[0].key_id, issued.key_id);
    let keys_first = blocking(move || f2.keys()).await.unwrap();
    let current: Vec<_> = keys_first.iter().filter(|k| k.current).collect();
    assert_eq!(current.len(), 1);
    assert_eq!(current[0].key_id, created.key_id);
    assert!(keys.iter().any(|k| k.key_id == created.key_id));
    assert!(keys.iter().any(|k| k.key_id == issued.key_id));
    assert!(
        keys.iter()
            .all(|k| k.last_used_ms.is_some() || k.key_id == issued.key_id)
    );
}

#[tokio::test]
async fn revoking_a_key_closes_its_socket_and_locks_it_out() {
    let server = start(4).await;
    let (first, created) = create(&server).await;
    let first2 = first.clone();
    let issued = blocking(move || first2.mint_key("second")).await.unwrap();
    let second = first.with_key(ConnectKey::parse(&issued.key).unwrap());

    // The second key listens live.
    let listener = CreatedInstance {
        instance_id: created.instance_id.clone(),
        key: issued.key.clone(),
        key_id: issued.key_id.clone(),
    };
    let mut events = watch(&server, &listener, None);
    assert_eq!(events.next().await, ClientEvent::Connected);

    // The first key revokes it.
    let key_id = issued.key_id.clone();
    blocking(move || first.revoke_key(&key_id)).await.unwrap();

    assert_eq!(events.next_event().await.event, GatewayEvent::KeyRevoked);
    // The stream reconnects, is refused, and stops for good.
    loop {
        match events.next().await {
            ClientEvent::Unauthorized => break,
            ClientEvent::Disconnected(_) | ClientEvent::Connected => {}
            other => panic!("unexpected {other:?}"),
        }
    }
    let err = blocking(move || second.status()).await.unwrap_err();
    assert_eq!(err, ClientError::Unauthorized);
}

#[tokio::test]
async fn bad_and_missing_keys_are_unauthorized() {
    let server = start(4).await;
    let (_, _created) = create(&server).await;

    let none = Gateway::new(server.url.clone(), None);
    assert_eq!(
        blocking(move || none.status()).await.unwrap_err(),
        ClientError::Unauthorized
    );
    let wrong = Gateway::new(server.url.clone(), Some(ConnectKey::from_bytes(&[42; 32])));
    assert_eq!(
        blocking(move || wrong.status()).await.unwrap_err(),
        ClientError::Unauthorized
    );
}

#[tokio::test]
async fn resuming_with_after_replays_only_newer_events() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;

    let mut events = watch(&server, &created, Some(0));
    assert_eq!(events.next_event().await.seq, 1);
    assert_eq!(events.next_event().await.seq, 2);
    drop(events);

    let g = gateway.clone();
    blocking(move || {
        g.send_text(&SendText {
            chat: "905321234567@s.whatsapp.net".into(),
            text: "merhaba".into(),
            quoted_id: None,
        })
    })
    .await
    .unwrap();

    let mut resumed = watch(&server, &created, Some(2));
    let only = resumed.next_event().await;
    assert_eq!(only.seq, 3);
    assert!(matches!(only.event, GatewayEvent::Message(_)));

    // A point the gateway never reached means "refetch".
    let mut ahead = watch(&server, &created, Some(500));
    assert_eq!(ahead.next_event().await.event, GatewayEvent::Resync);
}

#[tokio::test]
async fn send_text_echoes_and_is_stored() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created, None);
    assert_eq!(events.next().await, ClientEvent::Connected);

    let chat = "905321234567@s.whatsapp.net";
    let g = gateway.clone();
    let sent = blocking(move || {
        g.send_text(&SendText {
            chat: chat.into(),
            text: "selam".into(),
            quoted_id: None,
        })
    })
    .await
    .unwrap();

    match events.next_event().await.event {
        GatewayEvent::Message(m) => {
            assert_eq!(m.id, sent.message_id);
            assert!(m.from_me);
            assert_eq!(m.chat, chat);
            assert_eq!(m.body, MessageBody::Text("selam".into()));
        }
        other => panic!("expected Message, got {other:?}"),
    }

    let g = gateway.clone();
    let history = blocking(move || g.messages(chat, None, 10)).await.unwrap();
    assert_eq!(history.len(), 1);
    assert_eq!(history[0].id, sent.message_id);

    let chats = blocking(move || gateway.chats()).await.unwrap();
    assert_eq!(chats.len(), 1);
    assert_eq!(chats[0].jid, chat);
    assert_eq!(chats[0].unread, 0);
    assert_eq!(chats[0].last.as_ref().unwrap().id, sent.message_id);
}

#[tokio::test]
async fn read_presence_and_chat_state_reach_the_driver_and_bad_jids_are_rejected() {
    let server = start(4).await;
    let (gateway, _created) = create(&server).await;
    let chat = "905321234567@s.whatsapp.net";

    let g = gateway.clone();
    let results = blocking(move || {
        (
            g.mark_read(&MarkRead {
                chat: chat.into(),
                sender: None,
                ids: vec!["A".into(), "B".into()],
            }),
            g.set_presence(true),
            g.chat_state(&ChatState {
                chat: chat.into(),
                state: TypingState::Recording,
            }),
            g.subscribe_presence(chat),
        )
    })
    .await;
    results.0.unwrap();
    results.1.unwrap();
    results.2.unwrap();
    results.3.unwrap();
    assert_eq!(
        server.driver.ops(),
        [
            format!("read {chat} A,B"),
            "presence true".to_owned(),
            format!("chatstate {chat} Recording"),
            format!("subscribe {chat}"),
        ]
    );

    let g = gateway.clone();
    let (bad_chat, bad_subscribe, no_ids) = blocking(move || {
        (
            g.chat_state(&ChatState {
                chat: " ".into(),
                state: TypingState::Typing,
            }),
            g.subscribe_presence(""),
            g.mark_read(&MarkRead {
                chat: chat.into(),
                sender: None,
                ids: vec![],
            }),
        )
    })
    .await;
    for result in [bad_chat, bad_subscribe, no_ids] {
        match result {
            Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::BadRequest),
            other => panic!("expected BadRequest, got {other:?}"),
        }
    }
}

#[tokio::test]
async fn instance_cap_and_delete() {
    let server = start(1).await;
    let (gateway, created) = create(&server).await;

    let anonymous = Gateway::new(server.url.clone(), None);
    let err = blocking(move || {
        anonymous.create_instance(&CreateInstance {
            link: LinkRequest::Qr,
            app: AppId::Stops,
        })
    })
    .await
    .unwrap_err();
    assert!(matches!(err, ClientError::Api(e) if e.code == ErrorCode::LimitReached));

    // Deleting the instance frees the slot and kills its key.
    let mut events = watch(&server, &created, None);
    assert_eq!(events.next().await, ClientEvent::Connected);
    let g = gateway.clone();
    blocking(move || g.delete_instance()).await.unwrap();
    assert_eq!(
        blocking(move || gateway.status()).await.unwrap_err(),
        ClientError::Unauthorized
    );
    let (_, again) = create(&server).await;
    assert_ne!(again.instance_id, created.instance_id);
}

#[tokio::test]
async fn push_registration_and_healthz() {
    let server = start(4).await;
    let (gateway, _) = create(&server).await;
    blocking(move || {
        gateway.register_push(&rovsti_wa_proto::PushRegistration {
            app: AppId::Phone,
            token: "fcm-token".into(),
        })
    })
    .await
    .unwrap();

    let authority = server.url.as_str().replace("http://", "");
    let mut stream = tokio::net::TcpStream::connect(&authority).await.unwrap();
    stream
        .write_all(b"GET /healthz HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        .await
        .unwrap();
    let mut body = String::new();
    stream.read_to_string(&mut body).await.unwrap();
    assert!(body.starts_with("HTTP/1.1 200"), "{body}");
    assert!(body.ends_with("ok"), "{body}");
}

#[tokio::test]
async fn media_round_trip_is_downloaded_once_then_served_from_cache() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created, None);
    assert_eq!(events.next().await, ClientEvent::Connected);

    let chat = "905321234567@s.whatsapp.net";
    let file: Vec<u8> = (0..=255u8).cycle().take(10_000).collect();
    let (g, bytes) = (gateway.clone(), file.clone());
    let sent = blocking(move || {
        g.send_media(
            chat,
            MediaKind::Image,
            "image/png",
            Some("kedi & k\u{f6}pek"),
            bytes,
        )
    })
    .await
    .unwrap();

    match events.next_event().await.event {
        GatewayEvent::Message(m) => {
            assert_eq!(m.id, sent.message_id);
            assert!(m.from_me);
            assert_eq!(
                m.body,
                MessageBody::Media {
                    kind: MediaKind::Image,
                    caption: Some("kedi & k\u{f6}pek".into()),
                    mime: "image/png".into(),
                    size: 10_000,
                }
            );
        }
        other => panic!("expected Message, got {other:?}"),
    }

    let (g, id) = (gateway.clone(), sent.message_id.clone());
    assert_eq!(blocking(move || g.media(&id)).await.unwrap(), file);
    let (g, id) = (gateway.clone(), sent.message_id.clone());
    assert_eq!(blocking(move || g.media(&id)).await.unwrap(), file);
    // The second read came from the disk cache, not from WhatsApp.
    let downloads = server
        .driver
        .ops()
        .iter()
        .filter(|op| *op == "download")
        .count();
    assert_eq!(downloads, 1);
    let cache_dir = server
        .dir
        .path()
        .join("instances")
        .join(&created.instance_id)
        .join("media");
    assert_eq!(std::fs::read_dir(cache_dir).unwrap().count(), 1);

    // Text messages and unknown ids have no media.
    let g = gateway.clone();
    let text = blocking(move || {
        g.send_text(&SendText {
            chat: chat.into(),
            text: "x".into(),
            quoted_id: None,
        })
    })
    .await
    .unwrap();
    let g = gateway.clone();
    match blocking(move || g.media(&text.message_id)).await {
        Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::BadRequest),
        other => panic!("expected BadRequest, got {other:?}"),
    }
    match blocking(move || gateway.media("NOPE")).await {
        Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::NotFound),
        other => panic!("expected NotFound, got {other:?}"),
    }
}

#[tokio::test]
async fn media_over_the_limit_is_413_and_bad_requests_are_400() {
    let server = start_with(4, 1024).await;
    let (gateway, _created) = create(&server).await;
    let chat = "905321234567@s.whatsapp.net";

    let g = gateway.clone();
    let ok = blocking(move || {
        g.send_media(
            chat,
            MediaKind::Document,
            "application/pdf",
            None,
            vec![7; 1024],
        )
    })
    .await;
    assert!(ok.is_ok(), "{ok:?}");

    let g = gateway.clone();
    let too_big = blocking(move || {
        g.send_media(
            chat,
            MediaKind::Document,
            "application/pdf",
            None,
            vec![7; 1025],
        )
    })
    .await;
    match too_big {
        Err(ClientError::Http(m)) => assert!(m.contains("413"), "{m}"),
        other => panic!("expected a 413, got {other:?}"),
    }

    let g = gateway.clone();
    let (empty, bad_mime, bad_chat) = blocking(move || {
        (
            g.send_media(chat, MediaKind::Image, "image/png", None, vec![]),
            g.send_media(chat, MediaKind::Image, "png", None, vec![1]),
            g.send_media(" ", MediaKind::Image, "image/png", None, vec![1]),
        )
    })
    .await;
    for result in [empty, bad_mime, bad_chat] {
        match result {
            Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::BadRequest),
            other => panic!("expected BadRequest, got {other:?}"),
        }
    }
    // Only the one valid upload reached the driver's store.
    let g = gateway.clone();
    assert_eq!(
        blocking(move || g.messages(chat, None, 10))
            .await
            .unwrap()
            .len(),
        1
    );
}

#[tokio::test]
async fn reactions_reach_the_driver_and_update_the_message() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created, None);
    assert_eq!(events.next().await, ClientEvent::Connected);
    let chat = "905321234567@s.whatsapp.net";

    let g = gateway.clone();
    let sent = blocking(move || {
        g.send_text(&SendText {
            chat: chat.into(),
            text: "selam".into(),
            quoted_id: None,
        })
    })
    .await
    .unwrap();
    assert!(matches!(
        events.next_event().await.event,
        GatewayEvent::Message(_)
    ));

    let (g, id) = (gateway.clone(), sent.message_id.clone());
    blocking(move || {
        g.react(&SendReaction {
            chat: chat.into(),
            message_id: id,
            from_me: true,
            sender: None,
            emoji: "\u{2764}".into(),
        })
    })
    .await
    .unwrap();
    match events.next_event().await.event {
        GatewayEvent::MessageUpdate {
            chat: c,
            id,
            reactions,
            ..
        } => {
            assert_eq!(c, chat);
            assert_eq!(id, sent.message_id);
            assert_eq!(reactions.len(), 1);
            assert_eq!(reactions[0].emoji, "\u{2764}");
            assert!(reactions[0].from_me);
        }
        other => panic!("expected MessageUpdate, got {other:?}"),
    }

    let g = gateway.clone();
    let bad = blocking(move || {
        g.react(&SendReaction {
            chat: chat.into(),
            message_id: String::new(),
            from_me: true,
            sender: None,
            emoji: "x".into(),
        })
    })
    .await;
    assert!(matches!(bad, Err(ClientError::Api(e)) if e.code == ErrorCode::BadRequest));
}

#[tokio::test]
async fn groups_are_listed() {
    let server = start(4).await;
    let (gateway, _created) = create(&server).await;
    let group = GroupInfo {
        jid: "120363-1@g.us".into(),
        subject: "Aile".into(),
        participants: vec![Participant {
            jid: "905321234567@s.whatsapp.net".into(),
            admin: true,
        }],
    };
    server.driver.set_groups(vec![group.clone()]);
    let g = gateway.clone();
    assert_eq!(blocking(move || g.groups()).await.unwrap(), vec![group]);
}

fn wa_jid(n: u32) -> String {
    format!("9053212{n:05}@s.whatsapp.net")
}

#[tokio::test]
async fn lookup_answers_registered_and_caches() {
    let server = start(4).await;
    let (gateway, _created) = create(&server).await;
    let (yes, no) = (wa_jid(1), wa_jid(2));
    server.driver.set_registered(vec![yes.clone()]);
    let ask = vec![yes.clone(), no.clone(), yes.clone()];

    let g = gateway.clone();
    let first = blocking({
        let ask = ask.clone();
        move || g.lookup_users(&ask)
    })
    .await
    .unwrap();
    assert_eq!(first.len(), 2);
    assert_eq!(
        (first[0].jid.as_str(), first[0].on_whatsapp),
        (yes.as_str(), true)
    );
    assert_eq!(
        (first[1].jid.as_str(), first[1].on_whatsapp),
        (no.as_str(), false)
    );
    let lookups = |s: &Server| {
        s.driver
            .ops()
            .iter()
            .filter(|o| o.starts_with("lookup"))
            .count()
    };
    assert_eq!(server.driver.ops().last().unwrap(), "lookup 2");

    let g = gateway.clone();
    let second = blocking(move || g.lookup_users(&ask)).await.unwrap();
    assert_eq!(second, first);
    assert_eq!(lookups(&server), 1);
}

#[tokio::test]
async fn lookup_rejects_bad_and_too_many_jids() {
    let server = start(4).await;
    let (gateway, _created) = create(&server).await;
    let too_many: Vec<String> = (0..=50).map(wa_jid).collect();
    let cases = vec![
        vec![],
        vec!["120363-1@g.us".to_owned()],
        vec!["nonsense".to_owned()],
        too_many,
    ];
    for jids in cases {
        let g = gateway.clone();
        let err = blocking(move || g.lookup_users(&jids)).await.unwrap_err();
        assert!(is_code(&err, ErrorCode::BadRequest), "{err:?}");
    }
    assert!(server.driver.ops().iter().all(|o| !o.starts_with("lookup")));
}

#[tokio::test]
async fn lookup_is_rate_limited() {
    let server = start(4).await;
    let (gateway, _created) = create(&server).await;
    // 60 tokens: one call of 50 and one of 10 use them all.
    for (from, to) in [(0u32, 50u32), (50, 60)] {
        let g = gateway.clone();
        let jids: Vec<String> = (from..to).map(wa_jid).collect();
        assert_eq!(
            blocking(move || g.lookup_users(&jids)).await.unwrap().len(),
            (to - from) as usize
        );
    }
    let g = gateway.clone();
    let fresh = vec![wa_jid(100)];
    let err = blocking(move || g.lookup_users(&fresh)).await.unwrap_err();
    assert!(is_code(&err, ErrorCode::RateLimited), "{err:?}");
    // Cached numbers are still answered.
    let g = gateway.clone();
    let cached = vec![wa_jid(3)];
    assert_eq!(
        blocking(move || g.lookup_users(&cached))
            .await
            .unwrap()
            .len(),
        1
    );
}

#[tokio::test]
async fn lookup_needs_a_key() {
    let server = start(4).await;
    let (_, _created) = create(&server).await;
    let none = Gateway::new(server.url.clone(), None);
    let jids = vec![wa_jid(1)];
    assert_eq!(
        blocking(move || none.lookup_users(&jids))
            .await
            .unwrap_err(),
        ClientError::Unauthorized
    );
}

const PEER: &str = "905321234567@s.whatsapp.net";

fn frame(seed: i16) -> Vec<i16> {
    (0..960)
        .map(|n| seed.wrapping_mul(31).wrapping_add(n))
        .collect()
}

async fn next_call_phase(events: &mut Events) -> CallPhase {
    loop {
        if let GatewayEvent::Call(update) = events.next_event().await.event {
            return update.phase;
        }
    }
}

#[tokio::test]
async fn call_audio_echoes_frames_and_drops_wrong_sizes() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created, None);
    assert_eq!(events.next().await, ClientEvent::Connected);

    let g = gateway.clone();
    let call_id = blocking(move || g.place_call(PEER)).await.unwrap().call_id;
    assert_eq!(next_call_phase(&mut events).await, CallPhase::Ringing);
    assert_eq!(server.driver.calls().ops(), [format!("place {PEER}")]);

    let key = ConnectKey::parse(&created.key).unwrap();
    let (url, id) = (server.url.clone(), call_id.clone());
    let mut audio = blocking(move || call_audio(url, key.clone(), &id))
        .await
        .unwrap();

    // One socket per call: a second one is refused with 409 at the handshake.
    let key = ConnectKey::parse(&created.key).unwrap();
    let (url, id) = (server.url.clone(), call_id.clone());
    let second = blocking(move || call_audio(url, key, &id)).await;
    match second {
        Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::Conflict),
        other => panic!("expected a conflict, got {other:?}"),
    }

    // The wrong size never leaves the client; the fake echoes the rest.
    let frames = [frame(1), frame(2), frame(3)];
    audio.mic.send(frames[0].clone()).await.unwrap();
    audio.mic.send(vec![0; 959]).await.unwrap();
    audio.mic.send(frames[1].clone()).await.unwrap();
    audio.mic.send(vec![0; 961]).await.unwrap();
    audio.mic.send(frames[2].clone()).await.unwrap();
    for want in &frames {
        let got = tokio::time::timeout(Duration::from_secs(10), audio.speaker.next())
            .await
            .expect("timed out waiting for an echoed frame")
            .expect("audio ended");
        assert_eq!(&got, want);
    }
    assert!(
        tokio::time::timeout(Duration::from_millis(200), audio.speaker.next())
            .await
            .is_err(),
        "wrong-size frames must not come back"
    );

    // Hanging up ends the call and closes the audio socket.
    let (g, id) = (gateway.clone(), call_id.clone());
    blocking(move || g.end_call(&id)).await.unwrap();
    assert_eq!(
        next_call_phase(&mut events).await,
        CallPhase::Ended(EndReason::Hangup)
    );
    let end = tokio::time::timeout(Duration::from_secs(10), audio.speaker.next())
        .await
        .expect("the audio socket stayed open");
    assert!(end.is_none());
}

#[tokio::test]
async fn ending_a_ringing_outgoing_call_cancels_it_on_whatsapp() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created, None);
    assert_eq!(events.next().await, ClientEvent::Connected);

    let g = gateway.clone();
    let call_id = blocking(move || g.place_call(PEER)).await.unwrap().call_id;
    assert_eq!(next_call_phase(&mut events).await, CallPhase::Ringing);

    let (g, id) = (gateway.clone(), call_id.clone());
    blocking(move || g.end_call(&id)).await.unwrap();
    assert_eq!(
        next_call_phase(&mut events).await,
        CallPhase::Ended(EndReason::Hangup)
    );
    // The callee's phone stops ringing only if WhatsApp is told.
    assert_eq!(
        server.driver.calls().ops(),
        [format!("place {PEER}"), format!("terminate {call_id}")]
    );

    // Ending it again, or a call nobody knows, is refused and sends nothing.
    let (g, id) = (gateway.clone(), call_id.clone());
    match blocking(move || g.end_call(&id)).await {
        Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::Conflict),
        other => panic!("expected a conflict, got {other:?}"),
    }
    let g = gateway.clone();
    match blocking(move || g.end_call("NOPE")).await {
        Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::NotFound),
        other => panic!("expected not found, got {other:?}"),
    }
    assert_eq!(server.driver.calls().ops().len(), 2);
}

#[tokio::test]
async fn an_incoming_call_rings_and_is_answered_over_the_api() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created, None);
    assert_eq!(events.next().await, ClientEvent::Connected);

    let instance = wa_gateway::store::InstanceId::new(&created.instance_id);
    server
        .driver
        .ring(&instance, "INCOMING1", PEER, Some("Ayse"));
    loop {
        if let GatewayEvent::Call(update) = events.next_event().await.event {
            assert_eq!(update.call_id, "INCOMING1");
            assert_eq!(update.peer, PEER);
            assert_eq!(update.peer_name.as_deref(), Some("Ayse"));
            assert_eq!(update.phase, CallPhase::Ringing);
            break;
        }
    }

    let g = gateway.clone();
    blocking(move || g.accept_call("INCOMING1")).await.unwrap();
    assert_eq!(next_call_phase(&mut events).await, CallPhase::Connecting);
    let g = gateway.clone();
    blocking(move || g.set_muted("INCOMING1", true))
        .await
        .unwrap();
    assert!(server.driver.calls().lives()[0].is_muted());

    // Answering twice, and unknown calls, are refused.
    let g = gateway.clone();
    match blocking(move || g.accept_call("INCOMING1")).await {
        Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::Conflict),
        other => panic!("expected a conflict, got {other:?}"),
    }
    let g = gateway.clone();
    match blocking(move || g.reject_call("NOPE")).await {
        Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::NotFound),
        other => panic!("expected not found, got {other:?}"),
    }
}

#[tokio::test]
async fn the_call_list_needs_a_key_and_shows_the_instance_calls() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created, None);
    assert_eq!(events.next().await, ClientEvent::Connected);

    let none = Gateway::new(server.url.clone(), None);
    assert_eq!(
        blocking(move || none.calls()).await.unwrap_err(),
        ClientError::Unauthorized
    );
    let g = gateway.clone();
    assert!(blocking(move || g.calls()).await.unwrap().is_empty());

    let instance = wa_gateway::store::InstanceId::new(&created.instance_id);
    server.driver.ring(&instance, "LISTED1", PEER, Some("Ayse"));
    assert_eq!(next_call_phase(&mut events).await, CallPhase::Ringing);
    let g = gateway.clone();
    let calls = blocking(move || g.calls()).await.unwrap();
    assert_eq!(calls.len(), 1);
    assert_eq!(calls[0].update.call_id, "LISTED1");
    assert_eq!(calls[0].update.phase, CallPhase::Ringing);
    assert_eq!(calls[0].ended_ms, None);
}

#[tokio::test]
async fn revoking_your_own_key_is_allowed() {
    let server = start(4).await;
    let (first, created) = create(&server).await;
    let (a, b) = (first.clone(), first.clone());
    let own = created.key_id.clone();
    blocking(move || a.revoke_key(&own)).await.unwrap();
    assert_eq!(
        blocking(move || b.status()).await.unwrap_err(),
        ClientError::Unauthorized
    );
}

#[tokio::test]
async fn relinking_after_a_logout_shows_a_new_qr() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created, Some(0));
    assert_eq!(events.next().await, ClientEvent::Connected);
    // Qr, then Connected.
    events.next_event().await;
    events.next_event().await;

    let id = wa_gateway::store::InstanceId::new(created.instance_id.clone());
    wa_gateway::driver::WaDriver::logout(&*server.driver, &id)
        .await
        .unwrap();
    match events.next_event().await.event {
        GatewayEvent::State(status) => assert_eq!(status.state, InstanceState::LoggedOut),
        other => panic!("expected LoggedOut, got {other:?}"),
    }
    let g = gateway.clone();
    assert_eq!(
        blocking(move || g.status()).await.unwrap().state,
        InstanceState::LoggedOut
    );

    let g = gateway.clone();
    blocking(move || g.relink(&LinkRequest::Qr)).await.unwrap();
    let mut saw_qr = false;
    for _ in 0..4 {
        match events.next_event().await.event {
            GatewayEvent::Qr { payload, .. } => {
                assert!(payload.starts_with("fake-qr:"));
                saw_qr = true;
            }
            GatewayEvent::State(s) if s.state == InstanceState::Connected => break,
            _ => {}
        }
    }
    assert!(saw_qr, "relink must emit a Qr");
    // The same key still works: nothing was re-created.
    let status = blocking(move || gateway.status()).await.unwrap();
    assert_eq!(status.instance_id, created.instance_id);
}

fn is_code(e: &ClientError, code: ErrorCode) -> bool {
    matches!(e, ClientError::Api(a) if a.code == code)
}

fn in_ms(ms: i64) -> i64 {
    wa_gateway::now_ms() + ms
}

fn schedule_req(text: &str, send_at_ms: i64) -> ScheduleText {
    ScheduleText {
        chat: "905321234567@s.whatsapp.net".into(),
        text: text.into(),
        send_at_ms,
    }
}

async fn schedule(gateway: &Gateway, req: ScheduleText) -> Result<ScheduledText, ClientError> {
    let g = gateway.clone();
    blocking(move || g.schedule_text(&req)).await
}

async fn scheduled(gateway: &Gateway) -> Vec<ScheduledText> {
    let g = gateway.clone();
    blocking(move || g.scheduled()).await.unwrap()
}

/// Stores an item that is already due, which the API itself refuses.
async fn add_due(server: &Server, created: &CreatedInstance, id: &str, text: &str) {
    let item = ScheduledText {
        id: id.into(),
        chat: "905321234567@s.whatsapp.net".into(),
        text: text.into(),
        send_at_ms: in_ms(-1_000),
        created_ms: in_ms(-2_000),
        state: ScheduledState::Pending,
    };
    server
        .state
        .store()
        .add_scheduled(InstanceId::new(created.instance_id.clone()), item, 10)
        .await
        .unwrap();
}

async fn wait_for(
    gateway: &Gateway,
    id: &str,
    done: impl Fn(&ScheduledState) -> bool,
) -> ScheduledText {
    for _ in 0..200 {
        if let Some(item) = scheduled(gateway).await.into_iter().find(|s| s.id == id)
            && done(&item.state)
        {
            return item;
        }
        tokio::time::sleep(Duration::from_millis(25)).await;
    }
    panic!("scheduled text {id} never reached the expected state");
}

#[tokio::test]
async fn schedule_list_and_cancel() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created, None);
    assert_eq!(events.next().await, ClientEvent::Connected);

    let at = in_ms(3_600_000);
    let item = schedule(&gateway, schedule_req("later", at)).await.unwrap();
    assert_eq!(item.state, ScheduledState::Pending);
    assert_eq!((item.text.as_str(), item.send_at_ms), ("later", at));
    assert_eq!(item.id.len(), 24);
    loop {
        if let GatewayEvent::Scheduled(e) = events.next_event().await.event {
            assert_eq!(e, item);
            break;
        }
    }
    assert_eq!(scheduled(&gateway).await, vec![item.clone()]);
    // Nothing is sent before its time.
    assert!(server.driver.ops().is_empty());

    let (g, id) = (gateway.clone(), item.id.clone());
    blocking(move || g.cancel_scheduled(&id)).await.unwrap();
    assert!(scheduled(&gateway).await.is_empty());
    let (g, id) = (gateway.clone(), item.id.clone());
    let again = blocking(move || g.cancel_scheduled(&id)).await.unwrap_err();
    assert!(is_code(&again, ErrorCode::NotFound), "{again:?}");
}

#[tokio::test]
async fn bad_schedules_are_rejected() {
    let server = start(4).await;
    let (gateway, _created) = create(&server).await;
    let bad = |e: ClientError| is_code(&e, ErrorCode::BadRequest);

    let past = schedule(&gateway, schedule_req("x", in_ms(-5_000))).await;
    assert!(bad(past.unwrap_err()));
    let soon = schedule(&gateway, schedule_req("x", in_ms(10_000))).await;
    assert!(bad(soon.unwrap_err()));
    let far = schedule(&gateway, schedule_req("x", in_ms(366 * 24 * 3_600_000))).await;
    assert!(bad(far.unwrap_err()));
    let empty = schedule(&gateway, schedule_req("", in_ms(3_600_000))).await;
    assert!(bad(empty.unwrap_err()));
    let big = schedule(
        &gateway,
        schedule_req(&"a".repeat(64 * 1024 + 1), in_ms(3_600_000)),
    )
    .await;
    assert!(bad(big.unwrap_err()));
    let mut req = schedule_req("x", in_ms(3_600_000));
    req.chat = "nonsense".into();
    assert!(bad(schedule(&gateway, req).await.unwrap_err()));
    assert!(scheduled(&gateway).await.is_empty());

    // Exactly 64 KiB is fine.
    let ok = schedule(
        &gateway,
        schedule_req(&"a".repeat(64 * 1024), in_ms(3_600_000)),
    )
    .await;
    assert!(ok.is_ok());
}

#[tokio::test]
async fn at_most_200_pending_per_instance() {
    let server = start(4).await;
    let (gateway, _created) = create(&server).await;
    for n in 0..200 {
        schedule(&gateway, schedule_req(&format!("m{n}"), in_ms(3_600_000)))
            .await
            .unwrap();
    }
    let over = schedule(&gateway, schedule_req("one too many", in_ms(3_600_000))).await;
    assert!(is_code(&over.unwrap_err(), ErrorCode::LimitReached));
    // Another instance has its own allowance.
    let (other, _) = create(&server).await;
    schedule(&other, schedule_req("fine", in_ms(3_600_000)))
        .await
        .unwrap();
    assert!(scheduled(&other).await.len() == 1);
}

#[tokio::test]
async fn the_sweeper_sends_a_due_text_once_and_marks_it_sent() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created, None);
    assert_eq!(events.next().await, ClientEvent::Connected);
    add_due(&server, &created, "due1", "hello later").await;

    let sweeper = wa_gateway::schedule::spawn_sweeper_with(
        server.state.clone(),
        Duration::from_millis(20),
        Duration::from_secs(60),
    );
    let item = wait_for(&gateway, "due1", |s| {
        matches!(s, ScheduledState::Sent { .. })
    })
    .await;
    let ScheduledState::Sent { message_id } = &item.state else {
        unreachable!()
    };
    loop {
        if let GatewayEvent::Scheduled(e) = events.next_event().await.event
            && e.state != ScheduledState::Pending
        {
            assert_eq!(e.id, "due1");
            break;
        }
    }
    // Let several more ticks pass: it must not be sent again.
    tokio::time::sleep(Duration::from_millis(150)).await;
    sweeper.abort();
    let g = gateway.clone();
    let history = blocking(move || g.messages("905321234567@s.whatsapp.net", None, 10))
        .await
        .unwrap();
    assert_eq!(history.len(), 1);
    assert_eq!(&history[0].id, message_id);
    assert_eq!(history[0].body, MessageBody::Text("hello later".into()));
    // A sent item cannot be cancelled.
    let g = gateway.clone();
    let err = blocking(move || g.cancel_scheduled("due1"))
        .await
        .unwrap_err();
    assert!(is_code(&err, ErrorCode::NotFound));
}

#[tokio::test]
async fn a_driver_error_is_retried_once_then_fails() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    add_due(&server, &created, "bad1", "never arrives").await;
    server
        .driver
        .fail_sends(Some(DriverError::Failed("boom".into())));

    let sweeper = wa_gateway::schedule::spawn_sweeper_with(
        server.state.clone(),
        Duration::from_millis(20),
        Duration::from_millis(200),
    );
    // Within the retry delay it is still pending.
    tokio::time::sleep(Duration::from_millis(100)).await;
    assert_eq!(scheduled(&gateway).await[0].state, ScheduledState::Pending);
    let item = wait_for(&gateway, "bad1", |s| {
        matches!(s, ScheduledState::Failed { .. })
    })
    .await;
    assert_eq!(
        item.state,
        ScheduledState::Failed {
            reason: "boom".into()
        }
    );
    sweeper.abort();
}

#[tokio::test]
async fn a_retry_that_works_is_sent() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    add_due(&server, &created, "flaky", "second time lucky").await;
    server.driver.fail_sends(Some(DriverError::NotConnected));
    let sweeper = wa_gateway::schedule::spawn_sweeper_with(
        server.state.clone(),
        Duration::from_millis(20),
        Duration::from_millis(300),
    );
    tokio::time::sleep(Duration::from_millis(100)).await;
    server.driver.fail_sends(None);
    wait_for(&gateway, "flaky", |s| {
        matches!(s, ScheduledState::Sent { .. })
    })
    .await;
    sweeper.abort();
}

#[tokio::test]
async fn deleting_the_instance_removes_its_scheduled_texts() {
    let server = start(4).await;
    let (gateway, created) = create(&server).await;
    schedule(&gateway, schedule_req("x", in_ms(3_600_000)))
        .await
        .unwrap();
    let id = InstanceId::new(created.instance_id.clone());
    assert_eq!(
        server
            .state
            .store()
            .list_scheduled(id.clone(), 0)
            .await
            .unwrap()
            .len(),
        1
    );
    blocking(move || gateway.delete_instance()).await.unwrap();
    assert!(
        server
            .state
            .store()
            .list_scheduled(id, 0)
            .await
            .unwrap()
            .is_empty()
    );
    assert!(
        server
            .state
            .store()
            .due_scheduled(i64::MAX, 10)
            .await
            .unwrap()
            .is_empty()
    );
}
