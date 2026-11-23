//! Groups on the device (E6-WA R6): the gateway's answer stored, notice lines merged into the members, the left
//! flag, pages of a large group, and the v7 to v8 migration.

use rosty_core::wa_group::Role;
use rosty_wa_proto::{
    ChatMeta, CommunityRef, EventEnvelope, GatewayEvent, GroupInfo, MessageBody, MessageMeta,
    Participant, SystemNote, WaMessage,
};
use rusqlite::Connection;

use super::identity_tests::{columns, temp_dir};
use super::*;

const GROUP: &str = "120000000000000061@g.us";
const ADA: &str = "100000000061@s.whatsapp.net";
const BEN: &str = "100000000062@s.whatsapp.net";
const BEN_LID: &str = "777000000000062@lid";
const CEM_LID: &str = "777000000000063@lid";
const ME: &str = "100000000069@s.whatsapp.net";
const ME_LID: &str = "777000000000069@lid";

fn store() -> Store {
    Store::open_in_memory("inst1").unwrap()
}

fn own() -> Vec<String> {
    vec![ME.into(), ME_LID.into()]
}

fn env(seq: u64, event: GatewayEvent) -> EventEnvelope {
    EventEnvelope {
        seq,
        at_ms: 1_000_000 + seq as i64,
        event,
    }
}

fn person(jid: &str, lid: Option<&str>, role: &str, name: Option<&str>) -> Participant {
    Participant {
        jid: jid.into(),
        admin: role != "member",
        name: name.map(str::to_owned),
        lid: lid.map(str::to_owned),
        pn: jid.ends_with("@s.whatsapp.net").then(|| jid.to_owned()),
        role: Some(role.into()),
    }
}

fn family() -> GroupInfo {
    GroupInfo {
        jid: GROUP.into(),
        subject: "Aile".into(),
        participants: vec![
            person(ADA, None, "superadmin", Some("Ada")),
            person(BEN, Some(BEN_LID), "member", Some("Ben")),
            // A member known only by LID.
            person(CEM_LID, Some(CEM_LID), "member", Some("Cem")),
            person(ME, Some(ME_LID), "admin", None),
        ],
        description: Some("family".into()),
        announce: true,
        locked: true,
        created_ms: Some(900),
        creator: Some(ADA.into()),
        size: Some(4),
        community: Some(CommunityRef {
            kind: "announcement".into(),
            parent: Some("120000000000000060@g.us".into()),
        }),
        ..GroupInfo::default()
    }
}

fn note(
    id: &str,
    ts: i64,
    action: &str,
    by: &str,
    people: &[&str],
    value: Option<&str>,
) -> Vec<GatewayEvent> {
    GatewayEvent::Message(WaMessage {
        id: id.into(),
        chat: GROUP.into(),
        sender: Some(by.into()),
        from_me: false,
        timestamp_ms: ts,
        body: MessageBody::Unsupported,
        reactions: Vec::new(),
        chat_alt: None,
        sender_alt: None,
    })
    .with_meta(MessageMeta {
        system: Some(SystemNote::Group {
            action: action.into(),
            by: Some(by.into()),
            participants: people.iter().map(|p| (*p).to_owned()).collect(),
            value: value.map(str::to_owned),
        }),
        ..MessageMeta::default()
    })
}

/// A message event and the meta it carries, as the gateway sends a new line: the message, then its meta.
trait WithMeta {
    fn with_meta(self, meta: MessageMeta) -> Vec<GatewayEvent>;
}

impl WithMeta for GatewayEvent {
    fn with_meta(self, meta: MessageMeta) -> Vec<GatewayEvent> {
        let GatewayEvent::Message(m) = &self else {
            return vec![self];
        };
        let (chat, id) = (m.chat.clone(), m.id.clone());
        vec![
            self,
            GatewayEvent::MessageMeta {
                chat,
                id,
                body: None,
                meta: Box::new(meta),
            },
        ]
    }
}

fn apply(store: &mut Store, first_seq: u64, events: Vec<GatewayEvent>) {
    let envs: Vec<EventEnvelope> = events
        .into_iter()
        .enumerate()
        .map(|(i, e)| env(first_seq + i as u64, e))
        .collect();
    store.apply_events(&envs).unwrap();
}

fn roles(page: &GroupPage) -> Vec<(String, Role)> {
    page.members
        .iter()
        .map(|m| (m.jid.clone(), m.role))
        .collect()
}

#[test]
fn the_answer_is_kept_with_roles_and_me_first() {
    let mut store = store();
    assert!(
        store
            .group_read(GROUP, &own(), None, 0, 100)
            .unwrap()
            .group
            .is_none()
    );
    let stored = store.group_store(&family(), 1_000).unwrap();
    assert_eq!(stored.members, 4);
    let page = store.group_read(GROUP, &own(), None, 0, 100).unwrap();
    let group = page.group.clone().unwrap();
    assert_eq!(group.subject.as_deref(), Some("Aile"));
    assert!(group.settings.announce && group.settings.locked && !group.settings.left);
    assert_eq!(group.community_kind.as_deref(), Some("announcement"));
    assert_eq!(group.fetched_ms, 1_000);
    assert_eq!(page.total, 4);
    assert_eq!(page.admins, 2);
    assert_eq!(
        roles(&page),
        vec![
            (ME.to_owned(), Role::Admin),
            (ADA.to_owned(), Role::SuperAdmin),
            (BEN.to_owned(), Role::Member),
            (CEM_LID.to_owned(), Role::Member),
        ]
    );
    assert_eq!(page.members[2].alt.as_deref(), Some(BEN_LID));
    assert_eq!(page.view.my_role, Some(Role::Admin));
    assert!(page.view.abilities.manage && !page.view.announce_only);
    // Someone else reading (not a member): no role, nothing allowed.
    let stranger = store
        .group_read(GROUP, &["100000000099@s.whatsapp.net".into()], None, 0, 100)
        .unwrap();
    assert_eq!(stranger.view.my_role, None);
    assert!(!stranger.view.abilities.send);
}

#[test]
fn notice_lines_newer_than_the_fetch_move_the_members() {
    let mut store = store();
    store.group_store(&family(), 1_000).unwrap();
    let mut events = Vec::new();
    // Older than the fetch: already in the answer, changes nothing.
    events.extend(note("N0", 900, "remove", ADA, &[BEN], None));
    events.extend(note(
        "N1",
        2_000,
        "add",
        ADA,
        &["100000000064@s.whatsapp.net"],
        None,
    ));
    // Promoted by LID: the member listed by number follows.
    events.extend(note("N2", 2_100, "promote", ADA, &[BEN_LID], None));
    events.extend(note("N3", 2_200, "remove", ADA, &[CEM_LID], None));
    events.extend(note("N4", 2_300, "subject", ADA, &[], Some("Aile 2")));
    events.extend(note("N5", 2_400, "announce", ADA, &[], Some("off")));
    // I am demoted mid-action.
    events.extend(note("N6", 2_500, "demote", ADA, &[ME_LID], None));
    apply(&mut store, 1, events);
    let page = store.group_read(GROUP, &own(), None, 0, 100).unwrap();
    assert_eq!(
        roles(&page),
        vec![
            (ME.to_owned(), Role::Member),
            (ADA.to_owned(), Role::SuperAdmin),
            (BEN.to_owned(), Role::Admin),
            ("100000000064@s.whatsapp.net".to_owned(), Role::Member),
        ]
    );
    let group = page.group.unwrap();
    assert_eq!(group.subject.as_deref(), Some("Aile 2"));
    assert!(!group.settings.announce);
    assert!(!page.view.abilities.manage);
    // The lines are rows of the thread.
    let thread = store.thread(GROUP, 50, None).unwrap();
    assert!(thread.items.iter().any(|r| matches!(
        &r.system,
        Some(SystemNote::Group { action, .. }) if action == "promote"
    )));
}

#[test]
fn removed_while_reading_marks_the_group_left() {
    let mut store = store();
    store.group_store(&family(), 1_000).unwrap();
    let mut events = note("N1", 2_000, "remove", ADA, &[ME], None);
    events.push(GatewayEvent::ChatMeta {
        jid: GROUP.into(),
        meta: ChatMeta {
            left: true,
            ..ChatMeta::default()
        },
    });
    apply(&mut store, 1, events);
    let page = store.group_read(GROUP, &own(), None, 0, 100).unwrap();
    assert!(page.group.unwrap().settings.left);
    assert_eq!(page.view.my_role, None);
    assert!(!page.view.abilities.send && !page.view.abilities.leave);
    // The gateway's answer for a group I left keeps what was known.
    store
        .group_store(
            &GroupInfo {
                jid: GROUP.into(),
                left: true,
                ..GroupInfo::default()
            },
            3_000,
        )
        .unwrap();
    let page = store.group_read(GROUP, &own(), None, 0, 100).unwrap();
    assert_eq!(page.total, 3);
    assert_eq!(page.group.unwrap().subject.as_deref(), Some("Aile"));
    // Added back: a member again.
    apply(
        &mut store,
        10,
        vec![GatewayEvent::ChatMeta {
            jid: GROUP.into(),
            meta: ChatMeta::default(),
        }],
    );
    assert!(
        !store
            .group_read(GROUP, &own(), None, 0, 100)
            .unwrap()
            .group
            .unwrap()
            .settings
            .left
    );
}

#[test]
fn a_large_group_reads_in_pages_and_filters() {
    let mut store = store();
    let mut info = family();
    info.participants = (0..1_200)
        .map(|n| {
            person(
                &format!("1000000{n:05}@s.whatsapp.net"),
                None,
                if n == 7 { "admin" } else { "member" },
                Some(&format!("Üye {n:04}")),
            )
        })
        .collect();
    store.group_store(&info, 1_000).unwrap();
    let first = store.group_read(GROUP, &own(), None, 0, 100).unwrap();
    assert_eq!((first.total, first.members.len()), (1_200, 100));
    // Me (number 69 is mine), the admin, then by name.
    assert_eq!(first.members[0].jid, ME);
    assert_eq!(first.members[1].role, Role::Admin);
    assert_eq!(first.members[2].name.as_deref(), Some("Üye 0000"));
    let last = store.group_read(GROUP, &own(), None, 1_100, 100).unwrap();
    assert_eq!(last.members.len(), 100);
    let capped = store.group_read(GROUP, &own(), None, 0, 10_000).unwrap();
    assert_eq!(capped.members.len() as u32, MAX_MEMBER_PAGE);
    let by_name = store
        .group_read(GROUP, &own(), Some("üye 011"), 0, 100)
        .unwrap();
    assert_eq!(by_name.total, 10);
    let by_number = store
        .group_read(GROUP, &own(), Some("+1 000 000 01199"), 0, 100)
        .unwrap();
    assert_eq!(by_number.total, 1);
}

#[test]
fn another_account_empties_the_groups() {
    let mut store = store();
    store.group_store(&family(), 1_000).unwrap();
    let tx = store.conn.transaction().unwrap();
    link_state::wipe_account(
        &tx,
        &link_state::Wipe {
            keep_outbox: false,
            account_epoch: None,
            sweep: false,
        },
    )
    .unwrap();
    tx.commit().unwrap();
    assert!(
        store
            .group_read(GROUP, &own(), None, 0, 100)
            .unwrap()
            .group
            .is_none()
    );
}

#[test]
fn a_version_seven_file_gains_the_group_tables_and_keeps_its_rows() {
    let dir = temp_dir("groups-v7");
    let path = dir.join(file_name("inst1"));
    {
        let mut conn = Connection::open(&path).unwrap();
        schema::migrate_to(&mut conn, 7).unwrap();
        conn.execute_batch(&format!(
            "INSERT INTO sync_state (id, instance_id, last_seq, needs_resync, identity_backfill)
                VALUES (1, 'inst1', 7, 0, 'done');
             INSERT INTO chats (jid, name, is_group) VALUES ('{GROUP}', 'Aile', 1);"
        ))
        .unwrap();
    }
    let mut store = Store::open(&dir, "inst1").unwrap();
    assert_eq!(store.opened().unwrap().schema_version, 8);
    assert!(columns(&store.conn, "groups").contains(&"gone".to_owned()));
    assert!(columns(&store.conn, "group_members").contains(&"role".to_owned()));
    assert_eq!(store.chats(true).unwrap()[0].name.as_deref(), Some("Aile"));
    store.group_store(&family(), 1_000).unwrap();
    drop(store);
    let _ = std::fs::remove_dir_all(&dir);
}
