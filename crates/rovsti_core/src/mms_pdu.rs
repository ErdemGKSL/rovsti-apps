//! Byte-level MMS PDU codec, a subset of OMA-MMS-ENC 1.3.
//!
//! Only what the app needs for the carrier path is covered: decoding
//! `m-notification-ind` and `m-retrieve-conf`, encoding `m-send-req` and
//! `m-notify-resp`. Header values use the WSP encodings the specification
//! prescribes (short integers, long integers, value-length, text strings,
//! uintvar). The module does no I/O.

use thiserror::Error;

const FIELD_CC: u8 = 0x82;
const FIELD_CONTENT_LOCATION: u8 = 0x83;
const FIELD_CONTENT_TYPE: u8 = 0x84;
const FIELD_DATE: u8 = 0x85;
const FIELD_EXPIRY: u8 = 0x88;
const FIELD_FROM: u8 = 0x89;
const FIELD_MESSAGE_TYPE: u8 = 0x8C;
const FIELD_MMS_VERSION: u8 = 0x8D;
const FIELD_MESSAGE_SIZE: u8 = 0x8E;
const FIELD_STATUS: u8 = 0x95;
const FIELD_SUBJECT: u8 = 0x96;
const FIELD_TO: u8 = 0x97;
const FIELD_TRANSACTION_ID: u8 = 0x98;

const TYPE_SEND_REQ: u8 = 0x80;
const TYPE_NOTIFICATION_IND: u8 = 0x82;
const TYPE_NOTIFYRESP_IND: u8 = 0x83;
const TYPE_RETRIEVE_CONF: u8 = 0x84;

/// MMS version 1.3.
const VERSION_1_3: u8 = 0x93;
const ADDRESS_PRESENT: u8 = 0x80;
const INSERT_ADDRESS: u8 = 0x81;
const EXPIRY_RELATIVE: u8 = 0x81;
const STATUS_RETRIEVED: u8 = 0x81;
const STATUS_DEFERRED: u8 = 0x83;

/// WSP charset codes (MIBenum with the high bit set).
const CHARSET_LATIN1: u8 = 0x84;
const CHARSET_UTF8: u8 = 0xEA;

/// Content-type parameters (WSP 1.1 and 1.4 numbering).
const PARAM_CHARSET: u8 = 0x81;
const PARAM_NAME: u8 = 0x85;
const PARAM_FILENAME: u8 = 0x86;
const PARAM_NAME_14: u8 = 0x97;
const PARAM_FILENAME_14: u8 = 0x98;

const HEADER_CONTENT_LOCATION: u8 = 0x8E;
const HEADER_CONTENT_DISPOSITION: u8 = 0xAE;
const HEADER_CONTENT_DISPOSITION_14: u8 = 0xC5;

const MULTIPART_MIXED: u8 = 0xA3;

const PLMN_SUFFIX: &str = "/TYPE=PLMN";
const MAX_LONG_INTEGER_BYTES: usize = 8;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Error)]
pub enum PduError {
    #[error("the PDU ends before a complete field")]
    Truncated,
    #[error("unexpected PDU type 0x{0:02x}")]
    WrongType(u8),
    #[error("bad or missing header 0x{0:02x}")]
    BadHeader(u8),
    #[error("malformed multipart body")]
    BadMultipart,
}

/// `m-notification-ind`: the carrier announces a message to download.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NotificationInd {
    pub transaction_id: String,
    pub content_location: String,
    /// Sender number without the `/TYPE=PLMN` suffix.
    pub from: Option<String>,
    pub size: u32,
    /// Relative expiry in seconds. An absolute expiry yields `None` because
    /// the codec has no clock.
    pub expiry_s: Option<u32>,
}

/// One part of a multipart body.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PduPart {
    pub content_type: String,
    pub name: Option<String>,
    pub data: Vec<u8>,
}

/// `m-retrieve-conf`: the downloaded message.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RetrieveConf {
    pub transaction_id: Option<String>,
    pub from: Option<String>,
    pub to: Vec<String>,
    pub subject: Option<String>,
    /// Message date in seconds since the epoch.
    pub date_s: Option<u64>,
    pub parts: Vec<PduPart>,
}

// ---------------------------------------------------------------- reading

struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    fn is_empty(&self) -> bool {
        self.pos >= self.buf.len()
    }

    fn rest(&self) -> &'a [u8] {
        &self.buf[self.pos..]
    }

    fn peek(&self) -> Result<u8, PduError> {
        self.buf.get(self.pos).copied().ok_or(PduError::Truncated)
    }

    fn byte(&mut self) -> Result<u8, PduError> {
        let b = self.peek()?;
        self.pos += 1;
        Ok(b)
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8], PduError> {
        let end = self.pos.checked_add(n).ok_or(PduError::Truncated)?;
        let slice = self.buf.get(self.pos..end).ok_or(PduError::Truncated)?;
        self.pos = end;
        Ok(slice)
    }

    fn uintvar(&mut self) -> Result<u32, PduError> {
        let mut value: u32 = 0;
        for _ in 0..5 {
            let b = self.byte()?;
            value = value
                .checked_mul(128)
                .and_then(|v| v.checked_add(u32::from(b & 0x7F)))
                .ok_or(PduError::Truncated)?;
            if b & 0x80 == 0 {
                return Ok(value);
            }
        }
        Err(PduError::Truncated)
    }

    /// Reads a `Value-length` (short byte below 31, or 31 plus a uintvar)
    /// and returns the reader over the value bytes.
    fn value(&mut self) -> Result<Reader<'a>, PduError> {
        let first = self.byte()?;
        let len = match first {
            0..=30 => usize::from(first),
            31 => self.uintvar()? as usize,
            _ => return Err(PduError::Truncated),
        };
        Ok(Reader::new(self.take(len)?))
    }

    /// A NUL-terminated string, with an optional leading quote.
    fn text(&mut self) -> Result<&'a [u8], PduError> {
        let rest = self.rest();
        let end = rest
            .iter()
            .position(|&b| b == 0)
            .ok_or(PduError::Truncated)?;
        self.pos += end + 1;
        let s = &rest[..end];
        Ok(match s.first() {
            Some(0x7F | 0x22) => &s[1..],
            _ => s,
        })
    }

    fn text_utf8(&mut self) -> Result<String, PduError> {
        Ok(String::from_utf8_lossy(self.text()?).into_owned())
    }

    /// Short integer (high bit set) or long integer (length byte then
    /// big-endian bytes).
    fn integer(&mut self, field: u8) -> Result<u64, PduError> {
        let first = self.byte()?;
        if first & 0x80 != 0 {
            return Ok(u64::from(first & 0x7F));
        }
        let len = usize::from(first);
        if len == 0 || len > MAX_LONG_INTEGER_BYTES {
            return Err(PduError::BadHeader(field));
        }
        Ok(self
            .take(len)?
            .iter()
            .fold(0u64, |acc, &b| (acc << 8) | u64::from(b)))
    }

    /// Skips one header value of any encoding.
    fn skip_value(&mut self) -> Result<(), PduError> {
        match self.peek()? {
            0..=31 => {
                self.value()?;
            }
            32..=127 => {
                self.text()?;
            }
            _ => {
                self.pos += 1;
            }
        }
        Ok(())
    }

    /// `Encoded-string-value`: plain text, or value-length, charset, text.
    fn encoded_string(&mut self) -> Result<String, PduError> {
        match self.peek()? {
            0..=31 => {
                let mut inner = self.value()?;
                let charset = if inner.peek()? == 0 {
                    0
                } else {
                    // Well-known charsets are short integers; a long integer
                    // means one we cannot name, which decodes as UTF-8.
                    let b = inner.peek()?;
                    if b & 0x80 != 0 {
                        inner.pos += 1;
                        b
                    } else {
                        inner.integer(FIELD_SUBJECT)?;
                        0
                    }
                };
                Ok(decode_text(charset, inner.text()?))
            }
            _ => self.text_utf8(),
        }
    }

    /// An address value (`From`, `To`, `Cc`) without the `/TYPE=` suffix.
    fn address(&mut self) -> Result<String, PduError> {
        let s = self.encoded_string()?;
        Ok(match s.find("/TYPE=") {
            Some(i) => s[..i].to_owned(),
            None => s,
        })
    }
}

fn decode_text(charset: u8, bytes: &[u8]) -> String {
    match charset {
        CHARSET_LATIN1 => bytes.iter().map(|&b| char::from(b)).collect(),
        _ => String::from_utf8_lossy(bytes).into_owned(),
    }
}

fn expect_header(r: &mut Reader<'_>, expected: u8) -> Result<(), PduError> {
    let first = r.byte()?;
    if first != FIELD_MESSAGE_TYPE {
        return Err(PduError::BadHeader(first));
    }
    let ty = r.byte()?;
    if ty != expected {
        return Err(PduError::WrongType(ty));
    }
    Ok(())
}

// --------------------------------------------------------------- decoding

pub fn decode_notification_ind(bytes: &[u8]) -> Result<NotificationInd, PduError> {
    let mut r = Reader::new(bytes);
    expect_header(&mut r, TYPE_NOTIFICATION_IND)?;

    let mut transaction_id = None;
    let mut content_location = None;
    let mut from = None;
    let mut size = None;
    let mut expiry_s = None;

    while !r.is_empty() {
        let field = r.byte()?;
        match field {
            FIELD_TRANSACTION_ID => transaction_id = Some(r.text_utf8()?),
            FIELD_CONTENT_LOCATION => content_location = Some(r.text_utf8()?),
            FIELD_FROM => from = decode_from(&mut r)?,
            FIELD_MESSAGE_SIZE => {
                size =
                    Some(u32::try_from(r.integer(field)?).map_err(|_| PduError::BadHeader(field))?);
            }
            FIELD_EXPIRY => {
                let mut v = r.value()?;
                let kind = v.byte()?;
                let secs = v.integer(field)?;
                if kind == EXPIRY_RELATIVE {
                    expiry_s = Some(u32::try_from(secs).map_err(|_| PduError::BadHeader(field))?);
                }
            }
            0x80..=0xFF => r.skip_value()?,
            other => return Err(PduError::BadHeader(other)),
        }
    }

    Ok(NotificationInd {
        transaction_id: transaction_id.ok_or(PduError::BadHeader(FIELD_TRANSACTION_ID))?,
        content_location: content_location.ok_or(PduError::BadHeader(FIELD_CONTENT_LOCATION))?,
        from,
        size: size.ok_or(PduError::BadHeader(FIELD_MESSAGE_SIZE))?,
        expiry_s,
    })
}

fn decode_from(r: &mut Reader<'_>) -> Result<Option<String>, PduError> {
    let mut v = r.value()?;
    match v.byte()? {
        ADDRESS_PRESENT => Ok(Some(v.address()?)),
        INSERT_ADDRESS => Ok(None),
        _ => Err(PduError::BadHeader(FIELD_FROM)),
    }
}

pub fn decode_retrieve_conf(bytes: &[u8]) -> Result<RetrieveConf, PduError> {
    let mut r = Reader::new(bytes);
    expect_header(&mut r, TYPE_RETRIEVE_CONF)?;

    let mut conf = RetrieveConf {
        transaction_id: None,
        from: None,
        to: Vec::new(),
        subject: None,
        date_s: None,
        parts: Vec::new(),
    };

    loop {
        let field = r.byte()?;
        match field {
            FIELD_TRANSACTION_ID => conf.transaction_id = Some(r.text_utf8()?),
            FIELD_FROM => conf.from = decode_from(&mut r)?,
            FIELD_TO | FIELD_CC => {
                let address = r.address()?;
                if field == FIELD_TO {
                    conf.to.push(address);
                }
            }
            FIELD_SUBJECT => conf.subject = Some(r.encoded_string()?),
            FIELD_DATE => conf.date_s = Some(r.integer(field)?),
            FIELD_CONTENT_TYPE => {
                let (media, _) = decode_content_type(&mut r)?;
                if !is_multipart(&media) {
                    return Err(PduError::BadMultipart);
                }
                conf.parts = decode_multipart(r.rest())?;
                return Ok(conf);
            }
            0x80..=0xFF => r.skip_value()?,
            other => return Err(PduError::BadHeader(other)),
        }
    }
}

fn is_multipart(media: &str) -> bool {
    media.starts_with("multipart/") || media.starts_with("application/vnd.wap.multipart.")
}

/// Reads a `Content-type-value`: the media type and its `name` parameter.
fn decode_content_type(r: &mut Reader<'_>) -> Result<(String, Option<String>), PduError> {
    match r.peek()? {
        0..=31 => {
            let mut v = r.value()?;
            let media = decode_media(&mut v)?;
            let mut name = None;
            while !v.is_empty() {
                let param = v.peek()?;
                if param & 0x80 != 0 {
                    v.pos += 1;
                    match param {
                        PARAM_NAME | PARAM_NAME_14 | PARAM_FILENAME | PARAM_FILENAME_14 => {
                            let text = v.text_utf8()?;
                            name.get_or_insert(text);
                        }
                        _ => v.skip_value()?,
                    }
                } else {
                    v.text()?;
                    v.skip_value()?;
                }
            }
            Ok((media, name))
        }
        _ => Ok((decode_media(r)?, None)),
    }
}

fn decode_media(r: &mut Reader<'_>) -> Result<String, PduError> {
    let b = r.peek()?;
    if b & 0x80 != 0 {
        r.pos += 1;
        return Ok(well_known_media(b & 0x7F)
            .map(str::to_owned)
            .unwrap_or_else(|| "application/octet-stream".to_owned()));
    }
    r.text_utf8()
}

fn well_known_media(index: u8) -> Option<&'static str> {
    Some(match index {
        0x00 => "*/*",
        0x01 => "text/*",
        0x02 => "text/html",
        0x03 => "text/plain",
        0x06 => "text/x-vCalendar",
        0x07 => "text/x-vCard",
        0x0B => "multipart/*",
        0x0C => "multipart/mixed",
        0x0D => "multipart/form-data",
        0x0E => "multipart/byteranges",
        0x0F => "multipart/alternative",
        0x1C => "image/*",
        0x1D => "image/gif",
        0x1E => "image/jpeg",
        0x1F => "image/tiff",
        0x20 => "image/png",
        0x21 => "image/vnd.wap.wbmp",
        0x23 => "application/vnd.wap.multipart.mixed",
        0x26 => "application/vnd.wap.multipart.alternative",
        0x28 => "text/xml",
        0x33 => "application/vnd.wap.multipart.related",
        _ => return None,
    })
}

fn decode_multipart(body: &[u8]) -> Result<Vec<PduPart>, PduError> {
    let mut r = Reader::new(body);
    let count = r.uintvar()? as usize;
    // Every entry needs at least its two length bytes.
    if count > r.rest().len() / 2 {
        return Err(PduError::BadMultipart);
    }
    let mut parts = Vec::with_capacity(count);
    for _ in 0..count {
        let headers_len = r.uintvar()? as usize;
        let data_len = r.uintvar()? as usize;
        let mut headers = Reader::new(r.take(headers_len)?);
        let data = r.take(data_len)?.to_vec();
        parts.push(decode_part(&mut headers, data)?);
    }
    Ok(parts)
}

fn decode_part(headers: &mut Reader<'_>, data: Vec<u8>) -> Result<PduPart, PduError> {
    if headers.is_empty() {
        return Err(PduError::BadMultipart);
    }
    let (content_type, mut name) =
        decode_content_type(headers).map_err(|_| PduError::BadMultipart)?;
    while !headers.is_empty() {
        let field = headers.byte().map_err(|_| PduError::BadMultipart)?;
        let step = match field {
            HEADER_CONTENT_LOCATION => headers.text_utf8().map(|loc| {
                name.get_or_insert(loc);
            }),
            HEADER_CONTENT_DISPOSITION | HEADER_CONTENT_DISPOSITION_14 => {
                decode_disposition(headers).map(|file| {
                    if let Some(file) = file {
                        name.get_or_insert(file);
                    }
                })
            }
            0x80..=0xFF => headers.skip_value(),
            _ => headers.text().and_then(|_| headers.skip_value()),
        };
        step.map_err(|_| PduError::BadMultipart)?;
    }
    Ok(PduPart {
        content_type,
        name,
        data,
    })
}

fn decode_disposition(r: &mut Reader<'_>) -> Result<Option<String>, PduError> {
    let mut v = r.value()?;
    v.skip_value()?;
    let mut file = None;
    while !v.is_empty() {
        let param = v.byte()?;
        match param {
            PARAM_NAME | PARAM_NAME_14 | PARAM_FILENAME | PARAM_FILENAME_14 => {
                file = Some(v.text_utf8()?);
            }
            0x80..=0xFF => v.skip_value()?,
            _ => {
                v.pos -= 1;
                v.text()?;
                v.skip_value()?;
            }
        }
    }
    Ok(file)
}

// --------------------------------------------------------------- encoding

fn put_text(out: &mut Vec<u8>, s: &str) {
    // A NUL would end the string early, so it is dropped.
    out.extend(s.bytes().filter(|&b| b != 0));
    out.push(0);
}

fn put_uintvar(out: &mut Vec<u8>, mut value: u32) {
    let mut groups = [0u8; 5];
    let mut n = 0;
    loop {
        groups[n] = (value & 0x7F) as u8;
        n += 1;
        value >>= 7;
        if value == 0 {
            break;
        }
    }
    for i in (0..n).rev() {
        out.push(groups[i] | if i > 0 { 0x80 } else { 0 });
    }
}

fn put_value_length(out: &mut Vec<u8>, len: usize) {
    if len <= 30 {
        out.push(len as u8);
    } else {
        out.push(31);
        put_uintvar(out, u32::try_from(len).unwrap_or(u32::MAX));
    }
}

fn put_encoded_string(out: &mut Vec<u8>, s: &str) {
    let plain = s.is_ascii() && s.bytes().next().is_some_and(|b| (32..127).contains(&b));
    if plain {
        put_text(out, s);
    } else {
        // Value-length, UTF-8 charset, text.
        put_value_length(out, s.len() + 2);
        out.push(CHARSET_UTF8);
        put_text(out, s);
    }
}

/// `+15551234567` becomes `+15551234567/TYPE=PLMN`; e-mail addresses and
/// values that already carry a type stay as they are.
fn format_address(raw: &str) -> String {
    let raw = raw.trim();
    if raw.contains('@') || raw.contains("/TYPE=") {
        return raw.to_owned();
    }
    let cleaned: String = raw
        .chars()
        .filter(|c| c.is_ascii_digit() || *c == '+' || *c == '*' || *c == '#')
        .collect();
    format!("{cleaned}{PLMN_SUFFIX}")
}

fn put_part_content_type(out: &mut Vec<u8>, part: &PduPart) {
    let mut ct = Vec::new();
    put_text(&mut ct, &part.content_type);
    if part.content_type.starts_with("text/") {
        ct.extend_from_slice(&[PARAM_CHARSET, CHARSET_UTF8]);
    }
    if let Some(name) = &part.name {
        ct.push(PARAM_NAME);
        put_text(&mut ct, name);
    }
    put_value_length(out, ct.len());
    out.extend_from_slice(&ct);
}

/// Encodes an `m-send-req`. `to` entries are written as `+number/TYPE=PLMN`.
pub fn encode_send_req<S: AsRef<str>>(
    to: &[S],
    subject: Option<&str>,
    parts: &[PduPart],
    transaction_id: &str,
) -> Vec<u8> {
    let mut out = vec![FIELD_MESSAGE_TYPE, TYPE_SEND_REQ, FIELD_TRANSACTION_ID];
    put_text(&mut out, transaction_id);
    out.extend_from_slice(&[FIELD_MMS_VERSION, VERSION_1_3]);
    // The network fills in the sender.
    out.extend_from_slice(&[FIELD_FROM, 1, INSERT_ADDRESS]);
    for address in to {
        out.push(FIELD_TO);
        put_text(&mut out, &format_address(address.as_ref()));
    }
    if let Some(subject) = subject.filter(|s| !s.is_empty()) {
        out.push(FIELD_SUBJECT);
        put_encoded_string(&mut out, subject);
    }
    out.extend_from_slice(&[FIELD_CONTENT_TYPE, MULTIPART_MIXED]);

    put_uintvar(&mut out, u32::try_from(parts.len()).unwrap_or(u32::MAX));
    for part in parts {
        let mut headers = Vec::new();
        put_part_content_type(&mut headers, part);
        put_uintvar(&mut out, u32::try_from(headers.len()).unwrap_or(u32::MAX));
        put_uintvar(&mut out, u32::try_from(part.data.len()).unwrap_or(u32::MAX));
        out.extend_from_slice(&headers);
        out.extend_from_slice(&part.data);
    }
    out
}

/// Encodes an `m-notify-resp`: `retrieved` or deferred.
pub fn encode_notify_resp(transaction_id: &str, retrieved: bool) -> Vec<u8> {
    let mut out = vec![
        FIELD_MESSAGE_TYPE,
        TYPE_NOTIFYRESP_IND,
        FIELD_TRANSACTION_ID,
    ];
    put_text(&mut out, transaction_id);
    out.extend_from_slice(&[
        FIELD_MMS_VERSION,
        VERSION_1_3,
        FIELD_STATUS,
        if retrieved {
            STATUS_RETRIEVED
        } else {
            STATUS_DEFERRED
        },
    ]);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hex(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).expect("hex"))
            .collect()
    }

    const NOTIFICATION: &str = "8c82985431008d908918802b31353535313233343536372f545950453d504c4d4e008a808e020400880481020e1083687474703a2f2f6d6d732e6578616d706c652f6d3100";
    const RETRIEVE: &str = "8c84985237008d93850465000000891a802b343931353131323334353637382f545950453d504c4d4e00972b31353535303030313131312f545950453d504c4d4e009648690084a30201058348656c6c6f0904089e85612e6a706700ffd8ffd9";

    #[test]
    fn decodes_notification_vector() {
        let n = decode_notification_ind(&hex(NOTIFICATION)).expect("decode");
        assert_eq!(n.transaction_id, "T1");
        assert_eq!(n.content_location, "http://mms.example/m1");
        assert_eq!(n.from.as_deref(), Some("+15551234567"));
        assert_eq!(n.size, 1024);
        assert_eq!(n.expiry_s, Some(3600));
    }

    #[test]
    fn decodes_retrieve_vector() {
        let c = decode_retrieve_conf(&hex(RETRIEVE)).expect("decode");
        assert_eq!(c.transaction_id.as_deref(), Some("R7"));
        assert_eq!(c.from.as_deref(), Some("+4915112345678"));
        assert_eq!(c.to, ["+15550001111"]);
        assert_eq!(c.subject.as_deref(), Some("Hi"));
        assert_eq!(c.date_s, Some(0x6500_0000));
        assert_eq!(
            c.parts,
            [
                PduPart {
                    content_type: "text/plain".into(),
                    name: None,
                    data: b"Hello".to_vec(),
                },
                PduPart {
                    content_type: "image/jpeg".into(),
                    name: Some("a.jpg".into()),
                    data: vec![0xFF, 0xD8, 0xFF, 0xD9],
                },
            ]
        );
    }

    #[test]
    fn send_req_round_trips_through_the_decoder() {
        let parts = [
            PduPart {
                content_type: "text/plain".into(),
                name: Some("text_0.txt".into()),
                data: "Merhaba ğüş".as_bytes().to_vec(),
            },
            PduPart {
                content_type: "image/png".into(),
                name: None,
                data: vec![0x89, b'P', b'N', b'G', 0, 1, 2],
            },
        ];
        let mut bytes = encode_send_req(
            &["+15551234567", "0555 123 45 67"],
            Some("Tatil"),
            &parts,
            "tx9",
        );
        // The sender is inserted by the network, so a retrieve-conf decoder
        // sees the same structure once the type byte is swapped.
        assert_eq!(&bytes[..2], &[FIELD_MESSAGE_TYPE, TYPE_SEND_REQ]);
        bytes[1] = TYPE_RETRIEVE_CONF;
        let conf = decode_retrieve_conf(&bytes).expect("decode");
        assert_eq!(conf.transaction_id.as_deref(), Some("tx9"));
        assert_eq!(conf.from, None);
        assert_eq!(conf.to, ["+15551234567", "05551234567"]);
        assert_eq!(conf.subject.as_deref(), Some("Tatil"));
        assert_eq!(conf.parts, parts);
    }

    #[test]
    fn send_req_writes_plmn_addresses_and_utf8_subject() {
        let bytes = encode_send_req(&["+15551234567"], Some("Çay"), &[], "t");
        let text = String::from_utf8_lossy(&bytes);
        assert!(text.contains("+15551234567/TYPE=PLMN"));
        let conf = {
            let mut b = bytes.clone();
            b[1] = TYPE_RETRIEVE_CONF;
            decode_retrieve_conf(&b).expect("decode")
        };
        assert_eq!(conf.subject.as_deref(), Some("Çay"));
        assert!(conf.parts.is_empty());
    }

    #[test]
    fn notify_resp_bytes() {
        assert_eq!(
            encode_notify_resp("T1", true),
            [0x8C, 0x83, 0x98, b'T', b'1', 0, 0x8D, 0x93, 0x95, 0x81]
        );
        assert_eq!(*encode_notify_resp("T1", false).last().expect("byte"), 0x83);
    }

    #[test]
    fn truncated_inputs_are_reported() {
        let full = hex(NOTIFICATION);
        assert_eq!(decode_notification_ind(&[]), Err(PduError::Truncated));
        assert_eq!(
            decode_notification_ind(&full[..10]),
            Err(PduError::Truncated)
        );
        let retrieve = hex(RETRIEVE);
        assert_eq!(
            decode_retrieve_conf(&retrieve[..retrieve.len() - 3]),
            Err(PduError::Truncated)
        );
        assert_eq!(
            decode_retrieve_conf(&retrieve[..20]),
            Err(PduError::Truncated)
        );
    }

    #[test]
    fn wrong_type_is_reported() {
        assert_eq!(
            decode_notification_ind(&hex(RETRIEVE)),
            Err(PduError::WrongType(0x84))
        );
        assert_eq!(
            decode_retrieve_conf(&hex(NOTIFICATION)),
            Err(PduError::WrongType(0x82))
        );
    }

    #[test]
    fn bad_headers_are_reported() {
        assert_eq!(
            decode_notification_ind(&[0x01, 0x82]),
            Err(PduError::BadHeader(0x01))
        );
        // Valid prefix but no content location.
        let mut bytes = vec![0x8C, 0x82, 0x98, b'a', 0, 0x8E, 0x81];
        assert_eq!(
            decode_notification_ind(&bytes),
            Err(PduError::BadHeader(FIELD_CONTENT_LOCATION))
        );
        bytes.push(0x40);
        assert_eq!(
            decode_notification_ind(&bytes),
            Err(PduError::BadHeader(0x40))
        );
    }

    #[test]
    fn non_multipart_body_is_rejected() {
        let mut bytes = vec![0x8C, 0x84, 0x84, 0x83];
        bytes.extend_from_slice(b"hello");
        assert_eq!(decode_retrieve_conf(&bytes), Err(PduError::BadMultipart));
    }

    #[test]
    fn absurd_part_count_is_rejected() {
        let bytes = vec![0x8C, 0x84, 0x84, 0xA3, 0x7F];
        assert_eq!(decode_retrieve_conf(&bytes), Err(PduError::BadMultipart));
    }

    #[test]
    fn uintvar_round_trips() {
        for v in [0, 1, 127, 128, 16_383, 16_384, 2_097_152, u32::MAX >> 4] {
            let mut out = Vec::new();
            put_uintvar(&mut out, v);
            assert_eq!(Reader::new(&out).uintvar(), Ok(v));
        }
    }
}
