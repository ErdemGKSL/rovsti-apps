//! Golden JSON for the SMS payloads Kotlin sends (see `rovsti_core::sms`).

use rovsti_core::sms::{
    DeliveryState, SmsBox, SmsMessagesPayload, SmsSend, SmsSimsPayload, SmsStatus,
    SmsThreadsPayload,
};
use serde_json::{Value, json};

#[test]
fn threads_payload_with_nulls_and_unknown_fields() {
    let p: SmsThreadsPayload = serde_json::from_str(
        r#"{"v":1,"extra":true,"threads":[
            {"thread_id":3,"addresses":["+905321234567"],"snippet":"selam","date_ms":1700000000000,"unread":2},
            {"thread_id":4,"addresses":null,"snippet":null,"date_ms":null,"unread":null}]}"#,
    )
    .expect("parse");
    assert_eq!(p.threads.len(), 2);
    assert_eq!(p.threads[0].unread, 2);
    assert_eq!(p.threads[1].thread_id, 4);
    assert!(p.threads[1].addresses.is_empty());
    assert_eq!(p.threads[1].snippet, "");
    let empty: SmsThreadsPayload = serde_json::from_str("{}").expect("empty");
    assert!(empty.threads.is_empty());
    let null: SmsThreadsPayload = serde_json::from_str(r#"{"threads":null}"#).expect("null");
    assert!(null.threads.is_empty());
}

#[test]
fn messages_payload_box_values() {
    let p: SmsMessagesPayload = serde_json::from_str(
        r#"{"has_older":true,"sms":[
            {"id":1,"thread_id":3,"address":"+90532","body":"a","date_ms":10,"box":1,"read":true,"sub_id":null,"delivery":"none"},
            {"id":2,"thread_id":3,"address":"+90532","body":"b","date_ms":20,"box":2,"read":null,"sub_id":2,"delivery":"delivered"},
            {"id":3,"thread_id":3,"body":"c","box":99,"delivery":"from_the_future"},
            {"id":4,"thread_id":3,"box":null,"delivery":null},
            {"id":5,"thread_id":3,"box":5},
            {"id":6,"thread_id":3,"box":"queued"}],
          "mms":[{"id":7,"thread_id":3,"addresses":["+90532","+90533"],"from":null,"date_ms":30,
            "outgoing":false,"read":false,"sub_id":null,
            "parts":[{"content_type":"text/plain","text":"hi","path":null},
                     {"content_type":"image/jpeg","text":null,"path":"/data/x.jpg"}]}]}"#,
    )
    .expect("parse");
    assert!(p.has_older);
    let boxes: Vec<SmsBox> = p.sms.iter().map(|r| r.box_).collect();
    assert_eq!(
        boxes,
        vec![
            SmsBox::Inbox,
            SmsBox::Sent,
            SmsBox::Inbox,
            SmsBox::Inbox,
            SmsBox::Failed,
            SmsBox::Queued
        ]
    );
    assert_eq!(p.sms[1].sub_id, Some(2));
    assert!(!p.sms[1].read);
    assert_eq!(p.sms[1].delivery, DeliveryState::Delivered);
    assert_eq!(p.sms[2].delivery, DeliveryState::None);
    assert_eq!(p.sms[3].delivery, DeliveryState::None);
    let mms = &p.mms[0];
    assert_eq!(mms.addresses.len(), 2);
    assert_eq!(mms.from, None);
    assert_eq!(mms.parts[1].path.as_deref(), Some("/data/x.jpg"));
    assert_eq!(mms.text(), "hi");
}

#[test]
fn sims_status_and_send() {
    let sims: SmsSimsPayload = serde_json::from_str(
        r#"{"sims":[{"sub_id":1,"slot_index":0,"label":"Turkcell","color_argb":-16776961},
                    {"sub_id":2,"slot_index":null,"label":null,"color_argb":null}],
            "default_sub_id":null}"#,
    )
    .expect("sims");
    assert_eq!(sims.sims[0].color_argb, -16_776_961);
    assert_eq!(sims.sims[1].slot_index, None);
    assert_eq!(sims.default_sub_id, None);

    let status: SmsStatus = serde_json::from_str(
        r#"{"local_id":5,"uri":"content://sms/9","state":"failed","error_code":1}"#,
    )
    .expect("status");
    assert_eq!(status.state, DeliveryState::Failed);
    assert_eq!(status.error_code, Some(1));
    let odd: SmsStatus = serde_json::from_str(r#"{"local_id":5,"state":"weird"}"#).expect("odd");
    assert_eq!(odd.state, DeliveryState::None);
    assert_eq!(odd.uri, None);
}

#[test]
fn send_and_rows_serialise_to_the_documented_shape() {
    let send = SmsSend {
        local_id: 8,
        to: vec!["+905321234567".into()],
        body: "merhaba".into(),
        sub_id: None,
    };
    let value = serde_json::to_value(&send).expect("serialise");
    assert_eq!(
        value,
        json!({"local_id": 8, "to": ["+905321234567"], "body": "merhaba", "sub_id": null})
    );
    let back: SmsSend = serde_json::from_value(value).expect("round trip");
    assert_eq!(back, send);

    let page: SmsMessagesPayload =
        serde_json::from_str(r#"{"sms":[{"id":1,"thread_id":2,"box":4,"delivery":"pending"}]}"#)
            .expect("page");
    let value: Value = serde_json::to_value(&page).expect("serialise");
    assert_eq!(value["sms"][0]["box"], json!(4));
    assert_eq!(value["sms"][0]["delivery"], json!("pending"));
    let again: SmsMessagesPayload = serde_json::from_value(value).expect("round trip");
    assert_eq!(again, page);
}
