#![allow(clippy::unwrap_used)]

//! Polls, places, contact cards and "keep in chat" end to end (E6-WA R5): a real server on loopback with the
//! scripted driver, driven by the real `rosty_wa_client`.

use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use futures::channel::mpsc::UnboundedReceiver;
use rosty_wa_client::{ClientError, ClientEvent, EventStream, Gateway, subscribe};
use rosty_wa_proto::{
    AppId, ConnectKey, CreateInstance, CreatedInstance, ErrorCode, GatewayEvent, GatewayUrl,
    LinkRequest, MessageBody, MessageMeta, Poll, PollOption, PollVote, RichContent, SendRich,
    SharedContact, WaMessage,
};
use wa_gateway::config::Config;
use wa_gateway::driver::FakeDriver;
use wa_gateway::store::{InstanceId, MessageExtras, Store};
use wa_gateway::wa::convert::Converted;
use wa_gateway::{AppState, now_ms, serve};
use whatsapp_rust::wacore::poll as wa_poll;

const GROUP: &str = "120363000000000061@g.us";
const ALI: &str = "100000000061@s.whatsapp.net";
const BEN: &str = "100000000062@s.whatsapp.net";
const BEN_LID: &str = "777000000000062@lid";
const SECRET: [u8; 32] = [5; 32];

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
    /// The next `message_meta` of poll `id` whose choice of mine is `mine`.
    async fn poll_with(&mut self, id: &str, mine: &[&str]) -> Poll {
        loop {
            let poll = self.meta_of(id).await.poll.expect("a poll");
            if poll.my_votes == mine {
                return poll;
            }
        }
    }

    /// The next `message_meta` of message `id`.
    async fn meta_of(&mut self, id: &str) -> MessageMeta {
        loop {
            let next = tokio::time::timeout(Duration::from_secs(10), self.rx.next())
                .await
                .expect("timed out waiting for an event")
                .expect("event stream ended");
            if let ClientEvent::Event(e) = next
                && let GatewayEvent::MessageMeta { id: got, meta, .. } = e.event
                && got == id
            {
                return *meta;
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

fn poll_meta(options: &[&str]) -> Poll {
    Poll {
        question: "Lunch?".into(),
        options: options
            .iter()
            .map(|name| PollOption {
                name: (*name).into(),
                voters: vec![],
            })
            .collect(),
        selectable: 1,
        my_votes: vec![],
    }
}

/// Ali's poll `id` in the group, as the live path stores it.
fn alis_poll(id: &str, ts: i64) -> (WaMessage, MessageExtras) {
    (
        WaMessage {
            id: id.into(),
            chat: GROUP.into(),
            sender: Some(ALI.into()),
            from_me: false,
            timestamp_ms: ts,
            body: MessageBody::Unsupported,
            reactions: vec![],
            chat_alt: None,
            sender_alt: None,
        },
        MessageExtras {
            meta: MessageMeta {
                poll: Some(poll_meta(&["A", "B"])),
                ..Default::default()
            },
            secret: Some(SECRET.to_vec()),
            wire_sender: Some(ALI.into()),
            ..Default::default()
        },
    )
}

/// Ben's encrypted vote in poll `id`, made under `creator` and `voter` as the wire names them.
fn bens_vote(id: &str, picked: &[&str], creator: &str, voter: &str, at_ms: i64) -> Converted {
    let hashes: Vec<Vec<u8>> = picked
        .iter()
        .map(|n| wa_poll::compute_option_hash(n).to_vec())
        .collect();
    let (payload, iv) =
        wa_poll::encrypt_poll_vote_with_secret(&hashes, &SECRET, id, creator, voter).unwrap();
    Converted::PollVote {
        chat: GROUP.into(),
        poll_id: id.into(),
        voter: voter.into(),
        voter_alt: None,
        payload,
        iv: iv.to_vec(),
        at_ms,
    }
}

async fn stored_poll(gateway: &Gateway, id: &str) -> Poll {
    let g = gateway.clone();
    let page = blocking(move || g.messages_with_meta(GROUP, None, 50))
        .await
        .unwrap();
    page.into_iter()
        .find(|m| m.message.id == id)
        .and_then(|m| m.meta.poll)
        .expect("the poll is stored")
}

#[tokio::test]
async fn a_poll_a_place_and_cards_are_sent_and_stored() {
    let server = start().await;
    let (gateway, _created) = create(&server).await;
    let send = |content| {
        let g = gateway.clone();
        blocking(move || {
            g.send_rich(&SendRich {
                chat: GROUP.into(),
                quoted_id: None,
                content,
            })
        })
    };
    let poll = send(RichContent::Poll {
        question: " Lunch? ".into(),
        options: vec!["Pizza".into(), "Soup".into(), "".into()],
        multiple: true,
    })
    .await
    .unwrap();
    let place = send(RichContent::Location {
        latitude: 41.0258,
        longitude: 28.9744,
        name: Some("Galata".into()),
        address: None,
    })
    .await
    .unwrap();
    let card = |name: &str| SharedContact {
        display_name: name.into(),
        vcard: format!("BEGIN:VCARD\nFN:{name}\nTEL;waid=100000000063:+100000000063\nEND:VCARD"),
    };
    let cards = send(RichContent::Contacts {
        contacts: vec![card("Ada"), card("Can")],
    })
    .await
    .unwrap();

    let g = gateway.clone();
    let page = blocking(move || g.messages_with_meta(GROUP, None, 50))
        .await
        .unwrap();
    let meta_of = |id: &str| {
        page.iter()
            .find(|m| m.message.id == id)
            .unwrap()
            .meta
            .clone()
    };
    let stored = meta_of(&poll.message_id).poll.unwrap();
    assert_eq!(stored.question, "Lunch?");
    assert_eq!(
        stored.option_names(),
        vec!["Pizza".to_owned(), "Soup".to_owned()]
    );
    assert!(stored.multiple());
    let location = meta_of(&place.message_id).location.unwrap();
    assert_eq!(location.name.as_deref(), Some("Galata"));
    let shared = meta_of(&cards.message_id);
    assert_eq!(shared.contacts, vec![card("Ada"), card("Can")]);
    assert_eq!(shared.contact, Some(card("Ada")));
    assert!(
        server
            .driver
            .ops()
            .iter()
            .any(|op| op == &format!("rich {GROUP} contacts"))
    );

    // What the gateway would refuse never leaves the client.
    match send(RichContent::Contacts { contacts: vec![] }).await {
        Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::BadRequest),
        other => panic!("expected BadRequest, got {other:?}"),
    }
}

#[tokio::test]
async fn my_vote_is_counted_changed_and_taken_back() {
    let server = start().await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created).await;
    let g = gateway.clone();
    let sent = blocking(move || {
        g.send_rich(&SendRich {
            chat: GROUP.into(),
            quoted_id: None,
            content: RichContent::Poll {
                question: "Lunch?".into(),
                options: vec!["A".into(), "B".into()],
                multiple: false,
            },
        })
    })
    .await
    .unwrap();
    let vote = |options: &[&str]| {
        let g = gateway.clone();
        let req = PollVote {
            chat: GROUP.into(),
            message_id: sent.message_id.clone(),
            options: options.iter().map(|s| (*s).to_owned()).collect(),
        };
        blocking(move || g.poll_vote(&req))
    };

    vote(&["A"]).await.unwrap();
    let poll = events.poll_with(&sent.message_id, &["A"]).await;
    assert_eq!(poll.options[0].voters, vec!["me".to_owned()]);

    // A second vote replaces the first (tick the clock so it is newer).
    tokio::time::sleep(Duration::from_millis(5)).await;
    vote(&["B"]).await.unwrap();
    let poll = events.poll_with(&sent.message_id, &["B"]).await;
    assert!(poll.options[0].voters.is_empty());
    assert_eq!(poll.options[1].voters, vec!["me".to_owned()]);

    tokio::time::sleep(Duration::from_millis(5)).await;
    vote(&[]).await.unwrap();
    let poll = events.poll_with(&sent.message_id, &[]).await;
    assert!(poll.options.iter().all(|o| o.voters.is_empty()));

    // Two answers in a single-choice poll, a name that is no option, an unknown poll.
    for (options, code) in [
        (&["A", "B"][..], ErrorCode::BadRequest),
        (&["Z"][..], ErrorCode::BadRequest),
    ] {
        match vote(options).await {
            Err(ClientError::Api(e)) => assert_eq!(e.code, code),
            other => panic!("expected {code:?}, got {other:?}"),
        }
    }
    let g = gateway.clone();
    let unknown = PollVote {
        chat: GROUP.into(),
        message_id: "NOPE".into(),
        options: vec![],
    };
    match blocking(move || g.poll_vote(&unknown)).await {
        Err(ClientError::Api(e)) => assert_eq!(e.code, ErrorCode::NotFound),
        other => panic!("expected NotFound, got {other:?}"),
    }
}

#[tokio::test]
async fn a_vote_that_comes_first_waits_for_its_poll() {
    let server = start().await;
    let (gateway, created) = create(&server).await;
    let id = instance(&created);
    // Ben's vote arrives before Ali's poll.
    server
        .driver
        .deliver_converted(&id, bens_vote("P1", &["B"], ALI, BEN, now_ms() - 10))
        .await;
    let (poll, extras) = alis_poll("P1", now_ms() - 20);
    server.driver.deliver(&id, poll, extras).await;
    let stored = stored_poll(&gateway, "P1").await;
    assert_eq!(stored.options[1].voters, vec![BEN.to_owned()]);
    assert!(stored.options[0].voters.is_empty());

    // He changes his vote, now from his LID, with the pair learned: one voter, the newest vote.
    server
        .state
        .store()
        .learn_pairs(
            id.clone(),
            vec![wa_gateway::store::NewPair {
                lid: BEN_LID.into(),
                pn: BEN.into(),
                source: rosty_wa_proto::IdentitySource::PeerMessage,
                learned_ms: now_ms(),
            }],
        )
        .await
        .unwrap();
    let mut lid_vote = bens_vote("P1", &["A"], "777000000000061@lid", BEN_LID, now_ms());
    if let Converted::PollVote {
        voter, voter_alt, ..
    } = &mut lid_vote
    {
        // The live path names him by number and keeps the LID as his other id.
        *voter = BEN.into();
        *voter_alt = Some(BEN_LID.into());
    }
    // Ali's LID is known too, so the vote opens under both LIDs.
    server
        .state
        .store()
        .learn_pairs(
            id.clone(),
            vec![wa_gateway::store::NewPair {
                lid: "777000000000061@lid".into(),
                pn: ALI.into(),
                source: rosty_wa_proto::IdentitySource::PeerMessage,
                learned_ms: now_ms(),
            }],
        )
        .await
        .unwrap();
    server.driver.deliver_converted(&id, lid_vote).await;
    let stored = stored_poll(&gateway, "P1").await;
    assert_eq!(stored.options[0].voters, vec![BEN.to_owned()]);
    assert!(stored.options[1].voters.is_empty());
}

#[tokio::test]
async fn a_kept_message_stays_past_its_timer_until_the_keep_is_undone() {
    let server = start().await;
    let (gateway, created) = create(&server).await;
    let id = instance(&created);
    let old = now_ms() - 2 * 86_400_000;
    let (mut poll, mut extras) = alis_poll("K1", old);
    poll.id = "K1".into();
    extras.meta.expires_s = Some(86_400);
    server.driver.deliver(&id, poll, extras).await;
    server
        .driver
        .deliver_converted(
            &id,
            Converted::Keep {
                chat: GROUP.into(),
                target_id: "K1".into(),
                kept: true,
                at_ms: now_ms(),
            },
        )
        .await;
    assert_eq!(wa_gateway::expiry::purge(&server.state).await, 0);
    let g = gateway.clone();
    let page = blocking(move || g.messages_with_meta(GROUP, None, 50))
        .await
        .unwrap();
    assert!(
        page.iter()
            .find(|m| m.message.id == "K1")
            .unwrap()
            .meta
            .kept
    );

    server
        .driver
        .deliver_converted(
            &id,
            Converted::Keep {
                chat: GROUP.into(),
                target_id: "K1".into(),
                kept: false,
                at_ms: now_ms(),
            },
        )
        .await;
    assert_eq!(wa_gateway::expiry::purge(&server.state).await, 1);
}
