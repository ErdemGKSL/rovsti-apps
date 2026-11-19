#![allow(clippy::unwrap_used)]

//! The purge of disappearing messages (E6-WA R5): due messages leave the store and their media the cache.

use std::sync::Arc;

use rosty_wa_proto::{ConnectKey, KeyId, MediaKind, MessageBody, MessageMeta, WaMessage};
use wa_gateway::config::Config;
use wa_gateway::driver::FakeDriver;
use wa_gateway::media_cache::{DEFAULT_MAX_BYTES, MediaCache};
use wa_gateway::store::{InstanceId, MessageExtras, NewKey, Store};
use wa_gateway::{AppState, now_ms};

const CHAT: &str = "100000000021@s.whatsapp.net";

fn photo(id: &str, ts: i64) -> WaMessage {
    WaMessage {
        id: id.into(),
        chat: CHAT.into(),
        sender: None,
        from_me: false,
        timestamp_ms: ts,
        body: MessageBody::Media {
            kind: MediaKind::Image,
            caption: None,
            mime: "image/jpeg".into(),
            size: 3,
        },
        reactions: vec![],
        chat_alt: None,
        sender_alt: None,
    }
}

#[tokio::test]
async fn a_due_message_and_its_cached_media_are_purged() {
    let dir = tempfile::tempdir().unwrap();
    let mut config = Config::from_parts(None, &|_| None).unwrap();
    config.data_dir = dir.path().to_owned();
    let store = Store::open(&config.db_path()).unwrap();
    let instance = InstanceId::from_bytes(&[3; 12]);
    let key = ConnectKey::from_bytes(&[3; 32]);
    store
        .create_instance(
            instance.clone(),
            NewKey {
                key_id: KeyId::from_bytes(&[3; 8]),
                hash: key.hash(),
                label: "k".into(),
            },
            1,
            4,
        )
        .await
        .unwrap();
    let state = AppState::new(config, store, Arc::new(FakeDriver::new()));

    let timed = |seconds| MessageExtras {
        meta: MessageMeta {
            expires_s: Some(seconds),
            ..Default::default()
        },
        ..Default::default()
    };
    let now = now_ms();
    // Sent two days ago with a 24 hour timer: due. Sent now with the same timer: not yet.
    state
        .store()
        .insert_message_full(
            instance.clone(),
            photo("OLD", now - 2 * 86_400_000),
            timed(86_400),
        )
        .await
        .unwrap();
    state
        .store()
        .insert_message_full(instance.clone(), photo("NEW", now), timed(86_400))
        .await
        .unwrap();
    let cache = MediaCache::new(
        state.config().media_dir(instance.as_str()),
        DEFAULT_MAX_BYTES,
    );
    cache.put("OLD", b"old").unwrap();
    cache.put("NEW", b"new").unwrap();

    assert_eq!(wa_gateway::expiry::purge(&state).await, 1);
    let left: Vec<String> = state
        .store()
        .messages(instance.clone(), CHAT.into(), None, 10)
        .await
        .unwrap()
        .into_iter()
        .map(|m| m.id)
        .collect();
    assert_eq!(left, vec!["NEW".to_owned()]);
    assert_eq!(cache.get("OLD"), None, "the file went with the message");
    assert_eq!(cache.get("NEW").as_deref(), Some(&b"new"[..]));
    assert_eq!(wa_gateway::expiry::purge(&state).await, 0);
}
