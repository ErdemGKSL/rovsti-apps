//! A throwaway WhatsApp gateway on loopback, so the screenshot tests can show
//! the screens that need a linked instance (a chat, the Status page).
//!
//! It speaks just enough of the gateway's HTTP API for those screens and
//! answers everything else with 404. It never leaves the machine.

use std::{
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    thread,
};

use rovsti_core::{MessageDirection, ThreadId, inbox::InboxThread};
use rovsti_wa_proto::{
    ChatMeta, ChatSummary, DeliveryStatus, GroupInfo, InstanceState, InstanceStatus, MediaKind,
    MediaMeta, MessageBody, MessageMeta, Participant, Quoted, RichChat, RichMessage, StatusList,
    WaMessage,
};

use crate::backend::preview::{
    DAY, ELIF, HOUR, MINUTE,
    shots_data::{ANNE, CAN, FAMILY_TITLE, GROUP_FAMILY, user_jid},
};

/// What the server answers, built once from the fixture.
pub struct Fixture {
    pub chats: Vec<RichChat>,
    pub statuses: StatusList,
    pub family: Vec<RichMessage>,
    pub family_info: GroupInfo,
}

/// A `GET` the server understands, parsed from a request line.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Route {
    Instance,
    Chats,
    Messages(String),
    Statuses,
    Group(String),
    EmptyList,
    Unknown,
}

/// `"GET /v1/chats/abc%40g.us/messages?limit=50 HTTP/1.1"` is
/// `Route::Messages("abc@g.us")`.
pub fn route(request_line: &str) -> Route {
    let mut parts = request_line.split_whitespace();
    let (Some(method), Some(target)) = (parts.next(), parts.next()) else {
        return Route::Unknown;
    };
    let path = target.split('?').next().unwrap_or("");
    match (method, path) {
        ("GET", "/v1/instance") => Route::Instance,
        ("GET", "/v1/chats") => Route::Chats,
        ("GET", "/v1/statuses") => Route::Statuses,
        ("GET", "/v1/scheduled" | "/v1/calls" | "/v1/groups" | "/v1/keys") => Route::EmptyList,
        ("POST", "/v1/contacts/profiles" | "/v1/contacts/refresh") => Route::EmptyList,
        ("GET", p) if p.starts_with("/v1/chats/") && p.ends_with("/messages") => {
            let jid = &p["/v1/chats/".len()..p.len() - "/messages".len()];
            Route::Messages(unescape(jid))
        }
        ("GET", p) if p.starts_with("/v1/groups/") => {
            Route::Group(unescape(&p["/v1/groups/".len()..]))
        }
        _ => Route::Unknown,
    }
}

/// Undoes the `%40` the client puts in a JID.
fn unescape(text: &str) -> String {
    text.replace("%40", "@")
}

fn wa(
    id: &str,
    chat: &str,
    from: Option<(&str, &str)>,
    ago: i64,
    body: MessageBody,
    now_ms: i64,
) -> RichMessage {
    RichMessage {
        message: WaMessage {
            id: id.to_owned(),
            chat: chat.to_owned(),
            sender: from.map(|(number, _)| user_jid(number)),
            from_me: from.is_none(),
            timestamp_ms: now_ms - ago,
            body,
            reactions: Vec::new(),
        },
        meta: MessageMeta {
            sender_name: from.map(|(_, name)| name.to_owned()),
            status: from.is_none().then_some(DeliveryStatus::Read),
            ..MessageMeta::default()
        },
    }
}

fn text(body: &str) -> MessageBody {
    MessageBody::Text(body.to_owned())
}

/// The family group: sender names, a reply quote, a photo, 12 unread.
pub fn family_messages(now_ms: i64) -> Vec<RichMessage> {
    let chat = GROUP_FAMILY;
    let anne = Some((ANNE, "Anneciğim"));
    let can = Some((CAN, "Can Öztürk"));
    let elif = Some((ELIF, "Elif Kaya"));
    let mut quoted = wa(
        "m5",
        chat,
        can,
        3 * HOUR + 30 * MINUTE,
        text("Ayşe'ye ben söylerim"),
        now_ms,
    );
    quoted.meta.quoted = Some(Quoted {
        id: "m2".to_owned(),
        sender: None,
        preview: Some("Ben geliyorum anne, Elif de gelecek".to_owned()),
    });
    let mut photo = wa(
        "m6",
        chat,
        elif,
        3 * HOUR + 10 * MINUTE,
        MessageBody::Media {
            kind: MediaKind::Image,
            caption: Some("Dünkü fotoğraf :)".to_owned()),
            mime: "image/jpeg".to_owned(),
            size: 245_000,
        },
        now_ms,
    );
    photo.meta.media = Some(MediaMeta {
        width: Some(1200),
        height: Some(900),
        ..MediaMeta::default()
    });
    // Oldest first; the three first are read, the twelve last are unread.
    let mut all = vec![
        wa(
            "m1",
            chat,
            anne,
            DAY + 2 * HOUR,
            text("Günaydın çocuklar, bu hafta sonu kimler geliyor?"),
            now_ms,
        ),
        wa(
            "m2",
            chat,
            None,
            DAY,
            text("Ben geliyorum anne, Elif de gelecek"),
            now_ms,
        ),
        wa(
            "m3",
            chat,
            can,
            22 * HOUR,
            text("Cumartesi maçım var ama pazar kahvaltısına yetişirim"),
            now_ms,
        ),
        wa(
            "m4",
            chat,
            elif,
            4 * HOUR + 30 * MINUTE,
            text("Ben de geliyorum, sinemadan sonra direkt gelirim"),
            now_ms,
        ),
        quoted,
        photo,
        wa(
            "m7",
            chat,
            anne,
            3 * HOUR,
            text("Ayşe'yi de çağırın, o da gelsin"),
            now_ms,
        ),
        wa(
            "m8",
            chat,
            anne,
            2 * HOUR + 50 * MINUTE,
            text(
                "Börek için peynir lazım, gelirken köşedeki Ağaoğlu Market'ten 500 gram beyaz \
                 peynir ve bir demet maydanoz alabilirsiniz, çok teşekkürler kuzularım",
            ),
            now_ms,
        ),
        wa(
            "m9",
            chat,
            can,
            2 * HOUR + 40 * MINUTE,
            text("Tamam anne"),
            now_ms,
        ),
        wa(
            "m10",
            chat,
            elif,
            2 * HOUR + 35 * MINUTE,
            text("Ben maydanozu alırım"),
            now_ms,
        ),
        wa(
            "m11",
            chat,
            can,
            2 * HOUR + 20 * MINUTE,
            text("Saat kaçta başlıyoruz?"),
            now_ms,
        ),
        wa(
            "m12",
            chat,
            anne,
            2 * HOUR + 10 * MINUTE,
            text("10:30 gibi"),
            now_ms,
        ),
        wa(
            "m13",
            chat,
            elif,
            2 * HOUR + 5 * MINUTE,
            text("Tamamdır"),
            now_ms,
        ),
        wa(
            "m14",
            chat,
            anne,
            2 * HOUR,
            text("Pazar kahvaltısına herkes gelsin, börek yapıyorum"),
            now_ms,
        ),
    ];
    all.reverse();
    all
}

/// Builds the answers from the shots fixture.
pub fn fixture(now_ms: i64) -> Fixture {
    let chats = crate::backend::preview::shots_data::wa_threads(now_ms)
        .into_iter()
        .map(rich_chat)
        .collect();
    Fixture {
        chats,
        statuses: crate::backend::preview::shots_data::statuses(now_ms),
        family: family_messages(now_ms),
        family_info: GroupInfo {
            jid: GROUP_FAMILY.to_owned(),
            subject: FAMILY_TITLE.to_owned(),
            participants: [(ANNE, "Anneciğim"), (CAN, "Can Öztürk")]
                .into_iter()
                .map(|(number, name)| Participant {
                    jid: user_jid(number),
                    admin: number == ANNE,
                    name: Some(name.to_owned()),
                })
                .collect(),
            description: None,
            announce: false,
        },
    }
}

fn rich_chat(thread: InboxThread) -> RichChat {
    let ThreadId::WhatsApp(jid) = thread.id else {
        unreachable!("the fixture has WhatsApp threads only");
    };
    let message = WaMessage {
        id: format!("last-{jid}"),
        chat: jid.clone(),
        sender: thread.last.sender.as_ref().map(|n| user_jid(n.as_str())),
        from_me: thread.last.direction == MessageDirection::Outgoing,
        timestamp_ms: thread.last.timestamp_ms,
        body: MessageBody::Text(thread.last.body.clone()),
        reactions: Vec::new(),
    };
    RichChat {
        chat: ChatSummary {
            jid,
            name: thread.title.clone(),
            last: Some(message),
            unread: thread.unread,
            is_group: thread.is_group,
        },
        meta: ChatMeta::default(),
        last_meta: None,
    }
}

fn json<T: serde::Serialize>(value: &T) -> (u16, Vec<u8>) {
    (200, serde_json::to_vec(value).unwrap_or_default())
}

fn answer(fixture: &Fixture, route: &Route) -> (u16, Vec<u8>) {
    match route {
        Route::Instance => json(&InstanceStatus {
            instance_id: "shots".to_owned(),
            state: InstanceState::Connected,
            own_jid: Some(user_jid("+905320000000")),
            push_name: Some("Ben".to_owned()),
        }),
        Route::Chats => json(&fixture.chats),
        Route::Statuses => json(&fixture.statuses),
        Route::Messages(jid) if jid == GROUP_FAMILY => json(&fixture.family),
        Route::Messages(_) | Route::EmptyList => json(&Vec::<String>::new()),
        Route::Group(jid) if jid == GROUP_FAMILY => json(&fixture.family_info),
        Route::Group(_) | Route::Unknown => (
            404,
            br#"{"code":"not_found","message":"not in the shots fixture"}"#.to_vec(),
        ),
    }
}

fn read_head(stream: &mut TcpStream) -> Option<String> {
    let mut seen = Vec::new();
    let mut buf = [0u8; 2048];
    while !seen.windows(4).any(|w| w == b"\r\n\r\n") {
        let n = stream.read(&mut buf).ok()?;
        if n == 0 {
            return None;
        }
        seen.extend_from_slice(&buf[..n]);
        if seen.len() > 64 * 1024 {
            return None;
        }
    }
    let head = String::from_utf8_lossy(&seen).into_owned();
    // A POST body is not needed; drain what already arrived and move on.
    Some(head)
}

fn handle(mut stream: TcpStream, fixture: &Fixture) {
    let Some(head) = read_head(&mut stream) else {
        return;
    };
    let line = head.lines().next().unwrap_or("");
    let (status, body) = answer(fixture, &route(line));
    let reason = if status == 200 { "OK" } else { "Not Found" };
    let header = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: application/json\r\n\
         Content-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = stream.write_all(header.as_bytes());
    let _ = stream.write_all(&body);
}

/// Starts the server on a free loopback port and returns its URL. The thread
/// lives until the test process exits.
pub fn serve(now_ms: i64) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind the fake gateway");
    let port = listener.local_addr().expect("local address").port();
    let fixture = fixture(now_ms);
    thread::spawn(move || {
        for stream in listener.incoming().flatten() {
            handle(stream, &fixture);
        }
    });
    format!("http://127.0.0.1:{port}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn requests_map_to_routes() {
        assert_eq!(route("GET /v1/instance HTTP/1.1"), Route::Instance);
        assert_eq!(route("GET /v1/chats?x=1 HTTP/1.1"), Route::Chats);
        assert_eq!(
            route("GET /v1/chats/abc%40g.us/messages?limit=50 HTTP/1.1"),
            Route::Messages("abc@g.us".to_owned())
        );
        assert_eq!(
            route("GET /v1/groups/abc%40g.us HTTP/1.1"),
            Route::Group("abc@g.us".to_owned())
        );
        assert_eq!(route("GET /v1/events HTTP/1.1"), Route::Unknown);
        assert_eq!(route(""), Route::Unknown);
    }

    #[test]
    fn the_family_chat_is_newest_first_with_twelve_unread() {
        let now = 1_794_055_200_000;
        let list = family_messages(now);
        assert!(
            list.windows(2)
                .all(|w| w[0].message.timestamp_ms >= w[1].message.timestamp_ms)
        );
        assert!(list.iter().any(|m| m.meta.quoted.is_some()));
        assert!(
            list.iter()
                .any(|m| matches!(m.message.body, MessageBody::Media { .. }))
        );
        let family = fixture(now)
            .chats
            .into_iter()
            .find(|c| c.chat.jid == GROUP_FAMILY)
            .expect("family chat");
        assert_eq!(family.chat.unread, 12);
        assert!(list.len() >= 12);
    }
}
