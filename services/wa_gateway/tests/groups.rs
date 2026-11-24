#![allow(clippy::unwrap_used)]

//! Groups end to end (E6-WA R6): a real server on loopback with the scripted driver, driven by the real
//! `rosty_wa_client`. Info with roles and settings, bulk actions with per-person results, the invite link, preview
//! and join, join requests, a community's groups, leaving, and the notice lines group changes leave.

use std::sync::Arc;
use std::time::Duration;

use futures::StreamExt;
use futures::channel::mpsc::UnboundedReceiver;
use rosty_wa_client::{ClientError, ClientEvent, EventStream, Gateway, subscribe};
use rosty_wa_proto::{
    AppId, CommunityRef, ConnectKey, CreateGroup, CreateInstance, CreatedInstance, ErrorCode,
    GatewayEvent, GatewayUrl, GroupAction, GroupActionKind, GroupInfo, InviteCode, JoinRequest,
    LinkRequest, Participant, ParticipantResult, Subgroup, SystemNote,
};
use wa_gateway::config::Config;
use wa_gateway::driver::{DriverError, FakeDriver};
use wa_gateway::store::{InstanceId, Store};
use wa_gateway::{AppState, serve};

const GROUP: &str = "120363000000000071@g.us";
const ADA: &str = "100000000071@s.whatsapp.net";
const BEN: &str = "100000000072@s.whatsapp.net";
const CEM: &str = "100000000073@s.whatsapp.net";
const DOGA_LID: &str = "777000000000074@lid";

struct Server {
    url: GatewayUrl,
    driver: Arc<FakeDriver>,
    _state: AppState,
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
        _state: state,
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
    async fn next_where<T>(&mut self, mut pick: impl FnMut(GatewayEvent) -> Option<T>) -> T {
        loop {
            let next = tokio::time::timeout(Duration::from_secs(10), self.rx.next())
                .await
                .expect("timed out waiting for an event")
                .expect("event stream ended");
            if let ClientEvent::Event(e) = next
                && let Some(found) = pick(e.event)
            {
                return found;
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

fn member(jid: &str, role: &str) -> Participant {
    Participant {
        jid: jid.into(),
        admin: role != "member",
        role: Some(role.into()),
        ..Participant::default()
    }
}

fn family() -> GroupInfo {
    GroupInfo {
        jid: GROUP.into(),
        subject: "Aile".into(),
        participants: vec![
            member(ADA, "superadmin"),
            member(BEN, "admin"),
            member(DOGA_LID, "member"),
        ],
        locked: true,
        approval: true,
        created_ms: Some(1_700_000_000_000),
        creator: Some(ADA.into()),
        size: Some(3),
        community: Some(CommunityRef {
            kind: "sub".into(),
            parent: Some("120363000000000070@g.us".into()),
        }),
        ..GroupInfo::default()
    }
}

fn act(group: &str, action: GroupActionKind) -> GroupAction {
    GroupAction {
        group: group.into(),
        action,
    }
}

#[tokio::test]
async fn a_group_carries_roles_settings_and_community() {
    let server = start().await;
    let (gateway, created) = create(&server).await;
    let _events = watch(&server, &created).await;
    server.driver.set_groups(vec![family()]);
    let g = gateway.clone();
    let info = blocking(move || g.group(GROUP)).await.unwrap();
    assert_eq!(info.participants[0].role.as_deref(), Some("superadmin"));
    assert!(info.locked && info.approval && !info.left);
    assert_eq!(info.community.unwrap().kind, "sub");
    // A community's groups.
    server.driver.set_subgroups(vec![Subgroup {
        jid: "120363000000000072@g.us".into(),
        subject: "Duyurular".into(),
        size: Some(900),
        announcement: true,
        general: false,
    }]);
    let g = gateway.clone();
    let subs = blocking(move || g.subgroups("120363000000000070@g.us"))
        .await
        .unwrap();
    assert!(subs[0].announcement);
    // Not a group: refused before WhatsApp is asked.
    let g = gateway.clone();
    let err = blocking(move || g.subgroups(ADA)).await.unwrap_err();
    assert!(matches!(err, ClientError::Api(e) if e.code == ErrorCode::BadRequest));
}

#[tokio::test]
async fn a_bulk_add_answers_each_person() {
    let server = start().await;
    let (gateway, created) = create(&server).await;
    let _events = watch(&server, &created).await;
    server.driver.set_group_results(vec![
        ParticipantResult {
            jid: BEN.into(),
            error: Some("409".into()),
            invite: None,
        },
        ParticipantResult {
            jid: CEM.into(),
            error: Some("403".into()),
            invite: Some(InviteCode {
                code: "PrivacyInviteCode01".into(),
                expiration: 1_790_000_000,
            }),
        },
    ]);
    let g = gateway.clone();
    let done = blocking(move || {
        g.group_action(&act(
            GROUP,
            GroupActionKind::Add {
                participants: vec![ADA.into(), BEN.into(), CEM.into()],
            },
        ))
    })
    .await
    .unwrap();
    assert_eq!(done.results.len(), 3);
    assert_eq!(done.results[0].error, None);
    assert_eq!(done.results[1].error.as_deref(), Some("409"));
    let invite = done.results[2].invite.clone().unwrap();
    // The person whose privacy refused the add gets WhatsApp's own invite.
    let g = gateway.clone();
    blocking(move || {
        g.group_action(&act(
            GROUP,
            GroupActionKind::SendInvite {
                to: CEM.into(),
                code: invite.code,
                expiration: invite.expiration,
            },
        ))
    })
    .await
    .unwrap();
    let ops = server.driver.ops();
    assert!(
        ops.iter().any(|op| op.contains("\"send_invite\"")),
        "{ops:?}"
    );
    // An empty list never reaches WhatsApp (the client checks it first).
    let g = gateway.clone();
    let err = blocking(move || {
        g.group_action(&act(
            GROUP,
            GroupActionKind::Remove {
                participants: vec![],
            },
        ))
    })
    .await
    .unwrap_err();
    assert!(matches!(err, ClientError::Api(e) if e.code == ErrorCode::BadRequest));
}

#[tokio::test]
async fn a_refusal_is_a_conflict() {
    let server = start().await;
    let (gateway, created) = create(&server).await;
    let _events = watch(&server, &created).await;
    // Demoted on another phone a moment ago: WhatsApp refuses the change.
    server.driver.fail_next(
        "group",
        DriverError::Refused("WhatsApp refused the change (403)".into()),
    );
    let g = gateway.clone();
    let err = blocking(move || g.group_action(&act(GROUP, GroupActionKind::Announce { on: true })))
        .await
        .unwrap_err();
    assert!(matches!(err, ClientError::Api(e) if e.code == ErrorCode::Conflict));
}

#[tokio::test]
async fn the_invite_link_previews_and_joins() {
    let server = start().await;
    let (gateway, created) = create(&server).await;
    let _events = watch(&server, &created).await;
    server.driver.set_groups(vec![family()]);
    let g = gateway.clone();
    let link =
        blocking(move || g.group_action(&act(GROUP, GroupActionKind::InviteLink { reset: true })))
            .await
            .unwrap()
            .link
            .unwrap();
    assert_eq!(
        link,
        format!("https://chat.whatsapp.com/{}", FakeDriver::INVITE_CODE)
    );
    let g = gateway.clone();
    let shown = link.clone();
    let preview = blocking(move || g.invite_preview(&shown)).await.unwrap();
    assert_eq!((preview.subject.as_str(), preview.size), ("Aile", Some(3)));
    assert!(preview.approval);
    // A reset (or unknown) code is not found; something that is no code is a bad request.
    let g = gateway.clone();
    let err = blocking(move || g.invite_preview("https://chat.whatsapp.com/OldResetCode0001"))
        .await
        .unwrap_err();
    assert!(matches!(err, ClientError::Api(e) if e.code == ErrorCode::NotFound));
    let g = gateway.clone();
    let err = blocking(move || g.invite_preview("https://example.com/x"))
        .await
        .unwrap_err();
    assert!(matches!(err, ClientError::Api(e) if e.code == ErrorCode::BadRequest));
    // The group needs approval: the join is a request.
    let g = gateway.clone();
    let joined = blocking(move || g.join_group(&link)).await.unwrap();
    assert_eq!(joined.jid, GROUP);
    assert!(joined.pending);
    // The requests an admin sees.
    server.driver.set_join_requests(vec![JoinRequest {
        jid: CEM.into(),
        jid_alt: None,
        requested_ms: Some(1_790_000_000_000),
    }]);
    let g = gateway.clone();
    let requests = blocking(move || g.join_requests(GROUP)).await.unwrap();
    assert_eq!(requests[0].jid, CEM);
}

#[tokio::test]
async fn leaving_keeps_the_chat_readable_and_marked() {
    let server = start().await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created).await;
    server.driver.set_groups(vec![family()]);
    let g = gateway.clone();
    blocking(move || g.group_action(&act(GROUP, GroupActionKind::Leave)))
        .await
        .unwrap();
    let meta = events
        .next_where(|e| match e {
            GatewayEvent::ChatMeta { jid, meta } if jid == GROUP => Some(meta),
            _ => None,
        })
        .await;
    assert!(meta.left);
    let g = gateway.clone();
    assert!(blocking(move || g.group(GROUP)).await.unwrap().left);
    // WhatsApp no longer shows me the group: the answer says I left.
    server.driver.fail_next(
        "group_info",
        DriverError::Refused("not a member (403)".into()),
    );
    let g = gateway.clone();
    let gone = blocking(move || g.group(GROUP)).await.unwrap();
    assert!(gone.left && gone.participants.is_empty());
    // Another failure is not taken for leaving.
    server
        .driver
        .fail_next("group_info", DriverError::Failed("timeout".into()));
    let g = gateway.clone();
    assert!(blocking(move || g.group(GROUP)).await.is_err());
    // Joining again clears it.
    let mut back = family();
    back.approval = false;
    server.driver.set_groups(vec![back]);
    let g = gateway.clone();
    let joined = blocking(move || g.join_group(FakeDriver::INVITE_CODE))
        .await
        .unwrap();
    assert!(!joined.pending);
    let meta = events
        .next_where(|e| match e {
            GatewayEvent::ChatMeta { jid, meta } if jid == GROUP => Some(meta),
            _ => None,
        })
        .await;
    assert!(!meta.left);
}

#[tokio::test]
async fn group_changes_leave_lines_and_removal_marks_left() {
    let server = start().await;
    let (gateway, created) = create(&server).await;
    let mut events = watch(&server, &created).await;
    let sink = server.driver.sink_of(&instance(&created)).unwrap();
    let note = SystemNote::Group {
        action: "add".into(),
        by: Some(ADA.into()),
        participants: vec![BEN.into(), DOGA_LID.into()],
        value: None,
    };
    sink.group_note(GROUP.into(), "N1-0".into(), 1_790_000_000_000, note.clone())
        .await;
    // The same notification again keeps one line.
    sink.group_note(GROUP.into(), "N1-0".into(), 1_790_000_000_000, note.clone())
        .await;
    sink.group_left(GROUP, true, 1_790_000_001_000).await;
    let meta = events
        .next_where(|e| match e {
            GatewayEvent::ChatMeta { jid, meta } if jid == GROUP => Some(meta),
            _ => None,
        })
        .await;
    assert!(meta.left);
    let g = gateway.clone();
    let page = blocking(move || g.messages_with_meta(GROUP, None, 50))
        .await
        .unwrap();
    let lines: Vec<_> = page.iter().filter_map(|m| m.meta.system.clone()).collect();
    assert_eq!(lines, vec![note]);
    assert_eq!(page[0].message.sender.as_deref(), Some(ADA));
    // A group created here.
    let g = gateway.clone();
    let made = blocking(move || {
        g.create_group(&CreateGroup {
            subject: " Yeni ".into(),
            participants: vec![BEN.into()],
        })
    })
    .await
    .unwrap();
    assert!(made.jid.ends_with("@g.us") && made.results.is_empty());
    assert!(server.driver.ops().iter().any(|op| op == "create Yeni 1"));
}
