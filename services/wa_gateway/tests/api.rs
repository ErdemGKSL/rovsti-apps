#![allow(clippy::unwrap_used)]

//! End to end: a real server on loopback with the scripted driver, driven by
//! the real `rovsti_wa_client`.

use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use futures::channel::mpsc::UnboundedReceiver;
use rovsti_wa_client::{ClientError, ClientEvent, EventStream, Gateway, subscribe};
use rovsti_wa_proto::{
    AppId, ConnectKey, CreateInstance, CreatedInstance, ErrorCode, EventEnvelope, GatewayEvent,
    GatewayUrl, InstanceState, LinkRequest, MessageBody, SendText,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use wa_gateway::config::Config;
use wa_gateway::driver::FakeDriver;
use wa_gateway::store::Store;
use wa_gateway::{AppState, serve};

struct Server {
    url: GatewayUrl,
    driver: Arc<FakeDriver>,
    _dir: tempfile::TempDir,
}

async fn start(max_instances: u32) -> Server {
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::from_parts(None, &|_| None).unwrap();
    config.data_dir = dir.path().to_owned();
    config.max_instances = max_instances;
    let store = Store::open(&config.db_path()).unwrap();
    let driver = Arc::new(FakeDriver::new());
    let state = AppState::new(config, store, driver.clone());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(async move {
        serve(listener, state).await.unwrap();
    });
    Server {
        url: GatewayUrl::parse(&format!("http://127.0.0.1:{port}")).unwrap(),
        driver,
        _dir: dir,
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

    let keys = blocking(move || second.keys()).await.unwrap();
    assert_eq!(keys.len(), 2);
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
