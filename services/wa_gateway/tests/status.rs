#![allow(clippy::unwrap_used)]

//! Status updates end to end: a real server on loopback with the scripted
//! driver, driven by the real `rovsti_wa_client`.

use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use futures::channel::mpsc::UnboundedReceiver;
use rovsti_wa_client::{ClientError, ClientEvent, EventStream, Gateway, subscribe};
use rovsti_wa_proto::{
    AppId, ConnectKey, CreateInstance, CreatedInstance, ErrorCode, GatewayEvent, GatewayUrl,
    LinkRequest, MessageBody, STATUS_BROADCAST, StatusItem, StatusKind, StatusMedia, WaMessage,
};
use wa_gateway::config::Config;
use wa_gateway::driver::FakeDriver;
use wa_gateway::store::{InstanceId, MessageExtras, Store, StoredStatus};
use wa_gateway::{AppState, now_ms, serve};

struct Server {
    url: GatewayUrl,
    driver: Arc<FakeDriver>,
    state: AppState,
    _dir: tempfile::TempDir,
}

async fn start() -> Server {
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::from_parts(None, &|_| None).unwrap();
    config.data_dir = dir.path().to_owned();
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
        _dir: dir,
    }
}

/// The client is blocking; keep it off the runtime threads.
async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    tokio::task::spawn_blocking(f).await.unwrap()
}

async fn create(server: &Server) -> (Gateway, CreatedInstance) {
    let anonymous = Gateway::new(server.url.clone(), None);
    let (anonymous, created) = blocking(move || {
        anonymous
            .create_instance(&CreateInstance {
                link: LinkRequest::Qr,
                app: AppId::Messages,
            })
            .map(|c| (anonymous, c))
    })
    .await
    .unwrap();
    let key = ConnectKey::parse(&created.key).unwrap();
    (anonymous.with_key(key), created)
}

struct Events {
    _stream: EventStream,
    rx: UnboundedReceiver<ClientEvent>,
}

impl Events {
    async fn next_event(&mut self) -> GatewayEvent {
        loop {
            let next = tokio::time::timeout(Duration::from_secs(10), self.rx.next())
                .await
                .expect("timed out waiting for an event")
                .expect("event stream ended");
            if let ClientEvent::Event(e) = next {
                return e.event;
            }
        }
    }
}

async fn watch(server: &Server, created: &CreatedInstance) -> Events {
    let key = ConnectKey::parse(&created.key).unwrap();
    let (stream, rx) = subscribe(server.url.clone(), key, None).unwrap();
    let mut events = Events {
        _stream: stream,
        rx,
    };
    // Wait for the socket, so nothing published below is missed.
    loop {
        let next = tokio::time::timeout(Duration::from_secs(10), events.rx.next())
            .await
            .unwrap()
            .unwrap();
        if next == ClientEvent::Connected {
            return events;
        }
    }
}

fn instance(created: &CreatedInstance) -> InstanceId {
    InstanceId::new(created.instance_id.clone())
}

fn status(id: &str, author: &str, ts: i64) -> StoredStatus {
    StoredStatus {
        item: StatusItem {
            id: id.into(),
            author: author.into(),
            timestamp_ms: ts,
            kind: StatusKind::Text,
            text: Some("good morning".into()),
            background_argb: Some(0xFF1E_6E4F),
            ..Default::default()
        },
        participant: format!("{}@lid", &author[..3]),
        raw_proto: None,
        thumbnail: None,
    }
}

fn photo(id: &str, author: &str, ts: i64, bytes: Vec<u8>) -> StoredStatus {
    let mut s = status(id, author, ts);
    s.item.kind = StatusKind::Image;
    s.item.text = Some("beach".into());
    s.item.media = Some(StatusMedia {
        mime: "image/jpeg".into(),
        size: bytes.len() as u64,
        thumbnail: true,
        ..Default::default()
    });
    // The scripted driver "downloads" the stored message as it is.
    s.raw_proto = Some(bytes);
    s.thumbnail = Some(vec![0xFF, 0xD8, 0xFF, 0xE0]);
    s
}

#[tokio::test]
async fn statuses_are_listed_apart_from_the_chats() {
    let server = start().await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created).await;
    let id = instance(&created);
    let now = now_ms();
    let ayse = "905321234567@s.whatsapp.net";
    let bora = "905339876543@s.whatsapp.net";

    server
        .driver
        .deliver_status(&id, status("A1", ayse, now - 3_000))
        .await;
    assert_eq!(
        events.next_event().await,
        GatewayEvent::StatusChanged {
            author: Some(ayse.into())
        }
    );
    server
        .driver
        .deliver_status(&id, photo("B1", bora, now - 1_000, vec![7; 4096]))
        .await;
    let mut mine = status("M1", "me@s.whatsapp.net", now - 500);
    mine.item.from_me = true;
    server.driver.deliver_status(&id, mine).await;
    // A repeat is not announced again.
    server
        .driver
        .deliver_status(&id, status("A1", ayse, now - 3_000))
        .await;

    // A message to status@broadcast that took the chat path is dropped.
    server
        .driver
        .deliver(
            &id,
            WaMessage {
                id: "LEAK".into(),
                chat: STATUS_BROADCAST.into(),
                sender: None,
                from_me: false,
                timestamp_ms: now,
                body: MessageBody::Text("story".into()),
                reactions: vec![],
            },
            MessageExtras::default(),
        )
        .await;

    let g = gateway.clone();
    let list = blocking(move || g.statuses()).await.unwrap();
    assert_eq!(list.mine.len(), 1);
    let authors: Vec<&str> = list.authors.iter().map(|a| a.author.as_str()).collect();
    assert_eq!(authors, [bora, ayse]);
    assert_eq!(list.unviewed(), 2);
    assert_eq!(list.authors[1].items[0].background_argb, Some(0xFF1E_6E4F));

    let g = gateway.clone();
    let chats = blocking(move || g.chats_with_meta()).await.unwrap();
    assert!(chats.is_empty(), "{chats:?}");

    // Thumbnail and media of the photo; a text status has none.
    let g = gateway.clone();
    assert_eq!(
        blocking(move || g.status_thumbnail("B1")).await.unwrap(),
        vec![0xFF, 0xD8, 0xFF, 0xE0]
    );
    for _ in 0..2 {
        let g = gateway.clone();
        assert_eq!(
            blocking(move || g.status_media("B1")).await.unwrap(),
            vec![7; 4096]
        );
    }
    let downloads = server
        .driver
        .ops()
        .iter()
        .filter(|op| *op == "download")
        .count();
    assert_eq!(downloads, 1, "the second read comes from the cache");
    let g = gateway.clone();
    match blocking(move || g.status_media("A1")).await {
        Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::BadRequest),
        other => panic!("expected BadRequest, got {other:?}"),
    }
    let g = gateway.clone();
    match blocking(move || g.status_thumbnail("NOPE")).await {
        Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::NotFound),
        other => panic!("expected NotFound, got {other:?}"),
    }
}

#[tokio::test]
async fn viewing_sends_one_receipt_per_author_once() {
    let server = start().await;
    let (gateway, created) = create(&server).await;
    let id = instance(&created);
    let now = now_ms();
    let ayse = "905321234567@s.whatsapp.net";
    for (n, ts) in [("A1", now - 3_000), ("A2", now - 2_000)] {
        server.driver.deliver_status(&id, status(n, ayse, ts)).await;
    }
    let mut mine = status("M1", "me@s.whatsapp.net", now - 500);
    mine.item.from_me = true;
    server.driver.deliver_status(&id, mine).await;
    let mut events = watch(&server, &created).await;

    let g = gateway.clone();
    let ids = vec!["A1".to_owned(), "A2".to_owned(), "M1".to_owned()];
    blocking(move || g.mark_status_viewed(&ids)).await.unwrap();
    assert_eq!(
        events.next_event().await,
        GatewayEvent::StatusChanged {
            author: Some(ayse.into())
        }
    );
    let reads: Vec<String> = server
        .driver
        .ops()
        .into_iter()
        .filter(|op| op.starts_with("status-read"))
        .collect();
    assert_eq!(reads, ["status-read 905@lid A1,A2"]);

    // Seen already: no second receipt.
    let g = gateway.clone();
    blocking(move || g.mark_status_viewed(&["A1".to_owned()]))
        .await
        .unwrap();
    let reads = server
        .driver
        .ops()
        .iter()
        .filter(|op| op.starts_with("status-read"))
        .count();
    assert_eq!(reads, 1);

    let g = gateway.clone();
    let list = blocking(move || g.statuses()).await.unwrap();
    assert_eq!(list.unviewed(), 0);
    assert!(list.authors[0].items.iter().all(|i| i.viewed));

    let g = gateway.clone();
    match blocking(move || g.mark_status_viewed(&[])).await {
        Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::BadRequest),
        other => panic!("expected BadRequest, got {other:?}"),
    }
}

#[tokio::test]
async fn expired_statuses_are_purged_and_announced() {
    let server = start().await;
    let (gateway, created) = create(&server).await;
    let id = instance(&created);
    let mut short = status("S1", "905321234567@s.whatsapp.net", now_ms());
    short.item.expires_ms = now_ms() + 200;
    server.driver.deliver_status(&id, short).await;
    let mut events = watch(&server, &created).await;

    tokio::time::sleep(Duration::from_millis(300)).await;
    let g = gateway.clone();
    assert!(blocking(move || g.statuses()).await.unwrap().is_empty());
    assert_eq!(wa_gateway::status_routes::purge(&server.state).await, 1);
    assert_eq!(
        events.next_event().await,
        GatewayEvent::StatusChanged { author: None }
    );
    assert_eq!(wa_gateway::status_routes::purge(&server.state).await, 0);
}

#[tokio::test]
async fn statuses_need_a_key() {
    let server = start().await;
    let anonymous = Gateway::new(server.url.clone(), None);
    match blocking(move || anonymous.statuses()).await {
        Err(ClientError::Unauthorized) => {}
        other => panic!("expected Unauthorized, got {other:?}"),
    }
}
