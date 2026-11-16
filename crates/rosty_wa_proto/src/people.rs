//! People sync v3 (`_bmad-output/kotlin-ui/plans/people-sync-v3.md`): the server-side job that looks up the
//! device's numbers on WhatsApp, the status the device watches, the hash that tells "already synced" apart, and
//! the bundle the device downloads at the end.
//!
//! Pure: the gateway passes in time and randomness; the client and the device planner share these definitions.
//! Every type is additive on the wire: optional fields are skipped when absent, every enum has an `other`
//! catch-all, so a build that knows fewer fields or values still reads a newer one.

use std::io::BufRead;

use sha2::{Digest, Sha256};

/// Most numbers one start request may carry (more is `400 bad_request`).
pub const MAX_PEOPLE_NUMBERS: usize = 5_000;
/// The longest a status request waits for a change, in ms.
pub const PEOPLE_WAIT_MAX_MS: u64 = 25_000;
/// The default and largest size of one bundle part. A part may pass it by one record (one picture).
pub const BUNDLE_MAX_BYTES: u64 = 8 * 1024 * 1024;
/// What a reader accepts for one part: the part size, one picture of at most 16 MiB and the lines around it.
pub const BUNDLE_READ_LIMIT: u64 = BUNDLE_MAX_BYTES + 16 * 1024 * 1024 + 64 * 1024;
/// `Content-Type` of a bundle part.
pub const BUNDLE_CONTENT_TYPE: &str = "application/x-rosty-people-bundle";
/// The bundle format this build writes and reads.
pub const BUNDLE_VERSION: u32 = 1;
/// The longest JSON line of a bundle (a record is a few hundred bytes).
pub const BUNDLE_MAX_LINE: usize = 64 * 1024;
/// The largest picture a bundle record may announce.
pub const BUNDLE_MAX_PICTURE: u64 = 16 * 1024 * 1024;
/// The hash and picture id fields of a start entry longer than this are ignored (treated as absent).
pub const MAX_ENTRY_FIELD: usize = 128;
/// The domain line of [`people_hash`]; a new algorithm gets a new line.
pub const HASH_DOMAIN: &str = "rosty-people-v1\n";
/// The domain line of [`start_digest`].
pub const DIGEST_DOMAIN: &str = "rosty-people-start-v1\n";

/// `auto` follows the gateway's freshness rules; `full` ("Sync Now") asks WhatsApp about everything again. An
/// unknown value reads as `auto`.
#[derive(
    Clone, Copy, Debug, Default, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize,
)]
#[serde(rename_all = "snake_case")]
pub enum PeopleSyncMode {
    Full,
    #[default]
    #[serde(other)]
    Auto,
}

impl PeopleSyncMode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Full => "full",
            Self::Auto => "auto",
        }
    }
}

/// One number of a start request: `n` in E.164, `h` the hash the device stored for it (absent: nothing stored),
/// `p` the picture id whose file the device holds (absent: no usable file).
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PeopleEntry {
    pub n: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub h: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub p: Option<String>,
}

/// Body of `POST /v1/people/sync`.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PeopleSyncStart {
    #[serde(default)]
    pub mode: PeopleSyncMode,
    #[serde(default)]
    pub entries: Vec<PeopleEntry>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PeopleJobState {
    Running,
    /// Waiting out a limit or a disconnected WhatsApp session; see [`PeopleJobStatus::wait`].
    Waiting,
    /// The bundle can be downloaded; see [`PeopleJobStatus::result`].
    Ready,
    /// See [`PeopleJobStatus::error`].
    Failed,
    Cancelled,
    /// A state a newer gateway reports that this build does not know.
    #[serde(other)]
    Other,
}

impl PeopleJobState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Running => "running",
            Self::Waiting => "waiting",
            Self::Ready => "ready",
            Self::Failed => "failed",
            Self::Cancelled => "cancelled",
            Self::Other => "other",
        }
    }

    /// The gateway is still working on it.
    pub fn is_active(self) -> bool {
        matches!(self, Self::Running | Self::Waiting)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PeopleJobPhase {
    Lookup,
    Profiles,
    Pictures,
    Bundle,
    #[serde(other)]
    Other,
}

impl PeopleJobPhase {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Lookup => "lookup",
            Self::Profiles => "profiles",
            Self::Pictures => "pictures",
            Self::Bundle => "bundle",
            Self::Other => "other",
        }
    }
}

/// What a `waiting` job waits for.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PeopleWaitSource {
    /// WhatsApp's 429, or the cooldown the gateway runs after one.
    Whatsapp,
    /// The gateway's own allowance of the instance.
    Gateway,
    /// The WhatsApp session is down; the job tries again every minute.
    NotConnected,
    #[serde(other)]
    Other,
}

impl PeopleWaitSource {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Whatsapp => "whatsapp",
            Self::Gateway => "gateway",
            Self::NotConnected => "not_connected",
            Self::Other => "other",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PeopleWait {
    pub until_ms: i64,
    pub source: PeopleWaitSource,
}

/// The size of a ready job's bundle: records, records with picture bytes, and bytes in all.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PeopleJobResult {
    pub records: u32,
    pub pictures: u32,
    pub bytes: u64,
}

/// Error codes of a failed job.
pub mod job_error {
    /// Not ready within [`super::JOB_MAX_AGE_MS`].
    pub const EXPIRED: &str = "expired";
    /// WhatsApp kept failing a request.
    pub const WHATSAPP: &str = "whatsapp";
    /// The gateway itself failed (its store).
    pub const INTERNAL: &str = "internal";
}

/// A job that is not ready this long after it started fails as `expired`.
pub const JOB_MAX_AGE_MS: i64 = 6 * 3_600_000;
/// A finished job (and its numbers) is deleted this long after it finished.
pub const JOB_KEEP_MS: i64 = 24 * 3_600_000;
/// Lookup answers (negatives included) are kept at most this long.
pub const LOOKUP_KEEP_MS: i64 = 30 * 24 * 3_600_000;

#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PeopleJobError {
    /// [`job_error`]; a newer gateway may send others.
    pub code: String,
    pub message: String,
}

fn yes() -> bool {
    true
}

fn is_true(value: &bool) -> bool {
    *value
}

fn is_false(value: &bool) -> bool {
    !*value
}

/// What `POST /v1/people/sync` and `GET /v1/people/sync/{job_id}` answer.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PeopleJobStatus {
    pub job_id: String,
    /// Goes up with every change of state, phase or progress.
    pub rev: u64,
    /// On the start answer: false when another device's job runs on the instance (it was not changed; watch it
    /// and start again when it is over). Elsewhere always true; omitted when true.
    #[serde(default = "yes", skip_serializing_if = "is_true")]
    pub mine: bool,
    pub state: PeopleJobState,
    pub phase: PeopleJobPhase,
    /// Of the phase.
    #[serde(default)]
    pub done: u32,
    #[serde(default)]
    pub total: u32,
    /// How many numbers of the start request were accepted.
    #[serde(default)]
    pub numbers: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub wait: Option<PeopleWait>,
    /// An estimate of the time left while the job works.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub eta_ms: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<PeopleJobResult>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<PeopleJobError>,
    /// The instance's account epoch when the job started: the device's hashes belong to it.
    #[serde(default)]
    pub account_epoch: u32,
    #[serde(default)]
    pub created_ms: i64,
    #[serde(default)]
    pub updated_ms: i64,
    /// When the gateway forgets the job.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub expires_ms: Option<i64>,
}

/// One number of a start request after the gateway's rules: its place in the device's order (from 1; the bundle
/// cursor), the phone JID, and the device's hash and picture id.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AcceptedEntry {
    pub seq: u32,
    /// `+` and 7 to 15 digits.
    pub number: String,
    /// `<digits>@s.whatsapp.net`.
    pub jid: String,
    pub hash: Option<String>,
    pub picture: Option<String>,
}

/// The phone JID of an E.164 number: `+` and 7 to 15 digits, nothing else.
pub fn number_jid(number: &str) -> Option<String> {
    let digits = number.trim().strip_prefix('+')?;
    ((7..=15).contains(&digits.len()) && digits.bytes().all(|b| b.is_ascii_digit()))
        .then(|| format!("{digits}@s.whatsapp.net"))
}

fn field(value: Option<&String>) -> Option<String> {
    value
        .map(|v| v.trim())
        .filter(|v| !v.is_empty() && v.len() <= MAX_ENTRY_FIELD)
        .map(str::to_owned)
}

/// The entries the gateway works on, in the device's order: an invalid number is skipped, a repeat keeps the
/// first, an empty or oversized `h` or `p` reads as absent. `None` when there are more than
/// [`MAX_PEOPLE_NUMBERS`] entries.
pub fn accept_entries(entries: &[PeopleEntry]) -> Option<Vec<AcceptedEntry>> {
    if entries.len() > MAX_PEOPLE_NUMBERS {
        return None;
    }
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::with_capacity(entries.len());
    for entry in entries {
        let Some(jid) = number_jid(&entry.n) else {
            continue;
        };
        if !seen.insert(jid.clone()) {
            continue;
        }
        out.push(AcceptedEntry {
            seq: u32::try_from(out.len() + 1).unwrap_or(u32::MAX),
            number: entry.n.trim().to_owned(),
            jid,
            hash: field(entry.h.as_ref()),
            picture: field(entry.p.as_ref()),
        });
    }
    Some(out)
}

/// Appends `<byte length>:<bytes>,`.
fn put(hasher: &mut Sha256, value: &str) {
    hasher.update(value.len().to_string().as_bytes());
    hasher.update(b":");
    hasher.update(value.as_bytes());
    hasher.update(b",");
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// The fields of one number that reach the device's `people.json`; [`people_hash`] covers exactly these.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct HashInput<'a> {
    /// E.164, as the device sent it.
    pub number: &'a str,
    pub on_whatsapp: bool,
    /// The phone JID (`<digits>@s.whatsapp.net`).
    pub jid: Option<&'a str>,
    /// The LID JID (`<user>@lid`).
    pub lid: Option<&'a str>,
    pub push_name: Option<&'a str>,
    pub verified_name: Option<&'a str>,
    pub picture_id: Option<&'a str>,
}

/// The hash of what the device would store for one number (version 1).
///
/// SHA-256 over the ASCII line `rosty-people-v1\n` followed by seven fields, each written as
/// `<byte length in decimal>:<bytes>,`: the number (E.164), `1` or `0` for "on WhatsApp", the phone JID, the LID
/// JID, the push name, the verified name and the picture id. An absent field is the empty string; every field is
/// trimmed first. The hash is the first 8 bytes in lower-case hex (16 characters). The length prefix keeps the
/// encoding unambiguous whatever a name contains. Only the gateway computes it; the device stores it opaquely.
pub fn people_hash(input: &HashInput<'_>) -> String {
    let mut hasher = Sha256::new();
    hasher.update(HASH_DOMAIN.as_bytes());
    let text = |v: Option<&str>| v.map(str::trim).unwrap_or_default().to_owned();
    put(&mut hasher, input.number.trim());
    put(&mut hasher, if input.on_whatsapp { "1" } else { "0" });
    put(&mut hasher, &text(input.jid));
    put(&mut hasher, &text(input.lid));
    put(&mut hasher, &text(input.push_name));
    put(&mut hasher, &text(input.verified_name));
    put(&mut hasher, &text(input.picture_id));
    hex(&hasher.finalize()[..8])
}

/// The digest of a start request (its mode and accepted entries in order), full lower-case hex SHA-256: the same
/// request joins the running job, another one is another job.
pub fn start_digest(mode: PeopleSyncMode, entries: &[AcceptedEntry]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(DIGEST_DOMAIN.as_bytes());
    put(&mut hasher, mode.as_str());
    for entry in entries {
        put(&mut hasher, &entry.number);
        put(&mut hasher, entry.hash.as_deref().unwrap_or_default());
        put(&mut hasher, entry.picture.as_deref().unwrap_or_default());
    }
    hex(&hasher.finalize())
}

// ---- bundle ------------------------------------------------------------------------------------

/// The first line of a bundle part.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct BundleHeader {
    pub v: u32,
    pub job_id: String,
    pub after: u32,
}

/// What the gateway delivers for one number.
#[derive(Clone, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct PeopleRecord {
    /// The number's place in the start request (from 1).
    pub seq: u32,
    pub number: String,
    /// [`people_hash`] of this record: store it and send it as `h` next time.
    pub hash: String,
    pub on_whatsapp: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub jid: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lid: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub push_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub verified_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub picture_id: Option<String>,
    /// The picture follows this line: exactly this many raw bytes, then `\n`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub picture_bytes: Option<u64>,
    /// The record has a picture the gateway could not get: keep the old file, ask again next time.
    #[serde(default, skip_serializing_if = "is_false")]
    pub picture_missing: bool,
}

impl PeopleRecord {
    /// The record's own hash input.
    pub fn hash_input(&self) -> HashInput<'_> {
        HashInput {
            number: &self.number,
            on_whatsapp: self.on_whatsapp,
            jid: self.jid.as_deref(),
            lid: self.lid.as_deref(),
            push_name: self.push_name.as_deref(),
            verified_name: self.verified_name.as_deref(),
            picture_id: self.picture_id.as_deref(),
        }
    }
}

/// The last line of a complete part.
#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct BundleEnd {
    pub end: bool,
    /// The cursor for the next part (`after`); the request's `after` when the part has no record.
    pub last_seq: u32,
    /// More records follow in another part.
    pub more: bool,
}

/// Writes one bundle part into memory.
#[derive(Debug, Default)]
pub struct BundleWriter {
    bytes: Vec<u8>,
    last_seq: u32,
    records: u32,
}

fn json_line<T: serde::Serialize>(out: &mut Vec<u8>, value: &T) {
    // Serialising these plain structs cannot fail.
    if let Ok(line) = serde_json::to_vec(value) {
        out.extend_from_slice(&line);
    }
    out.push(b'\n');
}

impl BundleWriter {
    /// A part with its header line.
    pub fn new(job_id: &str, after: u32) -> Self {
        let mut bytes = Vec::new();
        json_line(
            &mut bytes,
            &BundleHeader {
                v: BUNDLE_VERSION,
                job_id: job_id.to_owned(),
                after,
            },
        );
        Self {
            bytes,
            last_seq: after,
            records: 0,
        }
    }

    /// Adds `record`; with `picture`, its bytes follow (and `picture_bytes` is set to their length).
    pub fn record(&mut self, record: &PeopleRecord, picture: Option<&[u8]>) {
        let mut record = record.clone();
        record.picture_bytes = picture.map(|p| p.len() as u64);
        json_line(&mut self.bytes, &record);
        if let Some(picture) = picture {
            self.bytes.extend_from_slice(picture);
            self.bytes.push(b'\n');
        }
        self.last_seq = self.last_seq.max(record.seq);
        self.records += 1;
    }

    /// Bytes written so far.
    pub fn len(&self) -> u64 {
        self.bytes.len() as u64
    }

    pub fn is_empty(&self) -> bool {
        self.records == 0
    }

    pub fn records(&self) -> u32 {
        self.records
    }

    /// Closes the part with its end line.
    pub fn finish(mut self, more: bool) -> Vec<u8> {
        json_line(
            &mut self.bytes,
            &BundleEnd {
                end: true,
                last_seq: self.last_seq,
                more,
            },
        );
        self.bytes
    }
}

/// Why a bundle part could not be read.
#[derive(Clone, Debug, PartialEq, Eq, thiserror::Error)]
pub enum BundleError {
    /// The part ended before its end line: keep what was committed and ask again from the cursor.
    #[error("the bundle part was cut")]
    Cut,
    /// A line that is not what the format says.
    #[error("bad bundle: {0}")]
    Bad(String),
    #[error("reading the bundle: {0}")]
    Io(String),
}

/// Reads up to and including `\n`, at most `max` bytes; the line without its `\n`. `Ok(None)` at the end.
fn read_line<R: BufRead>(reader: &mut R, max: usize) -> Result<Option<Vec<u8>>, BundleError> {
    let mut line = Vec::new();
    let mut limited = std::io::Read::take(reader, max as u64 + 1);
    let n = limited
        .read_until(b'\n', &mut line)
        .map_err(|e| BundleError::Io(e.to_string()))?;
    if n == 0 {
        return Ok(None);
    }
    if line.last() != Some(&b'\n') {
        return Err(if line.len() > max {
            BundleError::Bad("a line is too long".to_owned())
        } else {
            BundleError::Cut
        });
    }
    line.pop();
    Ok(Some(line))
}

/// One line of a part, by the keys it has.
#[derive(serde::Deserialize)]
#[serde(untagged)]
enum Line {
    End(BundleEnd),
    Record(PeopleRecord),
    Header(BundleHeader),
}

/// Reads one part from `reader`: the header, then each record with its picture bytes (handed to `on_record` in
/// order), then the end line. A part without its end line is [`BundleError::Cut`]; `on_record` failing stops the
/// read with its error.
pub fn read_bundle<R, E, F>(
    reader: &mut R,
    mut on_record: F,
) -> Result<(BundleHeader, BundleEnd), E>
where
    R: BufRead,
    E: From<BundleError>,
    F: FnMut(PeopleRecord, Option<Vec<u8>>) -> Result<(), E>,
{
    let bad = |what: &str| E::from(BundleError::Bad(what.to_owned()));
    let parse = |line: &[u8]| {
        serde_json::from_slice::<Line>(line).map_err(|e| E::from(BundleError::Bad(e.to_string())))
    };
    let header = match read_line(reader, BUNDLE_MAX_LINE)? {
        None => return Err(BundleError::Cut.into()),
        Some(line) => match parse(&line)? {
            Line::Header(header) if header.v == BUNDLE_VERSION => header,
            Line::Header(header) => {
                return Err(bad(&format!("bundle version {} is unknown", header.v)));
            }
            _ => return Err(bad("the part does not start with its header")),
        },
    };
    loop {
        let Some(line) = read_line(reader, BUNDLE_MAX_LINE)? else {
            return Err(BundleError::Cut.into());
        };
        match parse(&line)? {
            Line::End(end) if end.end => return Ok((header, end)),
            Line::End(_) | Line::Header(_) => return Err(bad("an unexpected line")),
            Line::Record(record) => {
                let picture = match record.picture_bytes {
                    None => None,
                    Some(n) if n > BUNDLE_MAX_PICTURE => return Err(bad("a picture is too big")),
                    Some(n) => {
                        let mut bytes = vec![0; usize::try_from(n).unwrap_or(0)];
                        match reader.read_exact(&mut bytes) {
                            Ok(()) => {}
                            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => {
                                return Err(BundleError::Cut.into());
                            }
                            Err(e) => return Err(BundleError::Io(e.to_string()).into()),
                        }
                        let mut newline = [0u8; 1];
                        match reader.read_exact(&mut newline) {
                            Ok(()) if newline[0] == b'\n' => {}
                            Ok(()) => return Err(bad("a picture is not followed by a newline")),
                            Err(_) => return Err(BundleError::Cut.into()),
                        }
                        Some(bytes)
                    }
                };
                on_record(record, picture)?;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn to_json<T: serde::Serialize>(v: &T) -> String {
        serde_json::to_string(v).unwrap()
    }

    fn pinned<T>(cases: &[(T, &str)])
    where
        T: serde::Serialize + serde::de::DeserializeOwned + PartialEq + std::fmt::Debug,
    {
        for (value, text) in cases {
            assert_eq!(&to_json(value), text);
            assert_eq!(&serde_json::from_str::<T>(text).unwrap(), value);
        }
    }

    fn status(state: PeopleJobState) -> PeopleJobStatus {
        PeopleJobStatus {
            job_id: "5f0c9a1b22d4e7a3".into(),
            rev: 42,
            mine: true,
            state,
            phase: PeopleJobPhase::Profiles,
            done: 120,
            total: 340,
            numbers: 812,
            wait: None,
            eta_ms: None,
            result: None,
            error: None,
            account_epoch: 3,
            created_ms: 1_794_999_000_000,
            updated_ms: 1_794_999_100_000,
            expires_ms: Some(1_795_020_600_000),
        }
    }

    #[test]
    fn the_start_request_is_pinned() {
        pinned(&[
            (
                PeopleSyncStart {
                    mode: PeopleSyncMode::Auto,
                    entries: vec![
                        PeopleEntry {
                            n: "+100000000001".into(),
                            h: Some("9a41c0de5b7e1f02".into()),
                            p: Some("1716839201".into()),
                        },
                        PeopleEntry {
                            n: "+100000000002".into(),
                            ..Default::default()
                        },
                    ],
                },
                r#"{"mode":"auto","entries":[{"n":"+100000000001","h":"9a41c0de5b7e1f02","p":"1716839201"},{"n":"+100000000002"}]}"#,
            ),
            (
                PeopleSyncStart {
                    mode: PeopleSyncMode::Full,
                    entries: vec![],
                },
                r#"{"mode":"full","entries":[]}"#,
            ),
        ]);
        // An unknown mode reads as auto; a missing one too.
        let future: PeopleSyncStart =
            serde_json::from_str(r#"{"mode":"turbo","entries":[],"extra":1}"#).unwrap();
        assert_eq!(future.mode, PeopleSyncMode::Auto);
        let bare: PeopleSyncStart = serde_json::from_str("{}").unwrap();
        assert_eq!(bare, PeopleSyncStart::default());
    }

    #[test]
    fn every_job_state_is_pinned() {
        let running = status(PeopleJobState::Running);
        let waiting = PeopleJobStatus {
            wait: Some(PeopleWait {
                until_ms: 1_795_000_000_000,
                source: PeopleWaitSource::Whatsapp,
            }),
            eta_ms: Some(95_000),
            ..status(PeopleJobState::Waiting)
        };
        let ready = PeopleJobStatus {
            phase: PeopleJobPhase::Bundle,
            result: Some(PeopleJobResult {
                records: 3,
                pictures: 1,
                bytes: 61_234,
            }),
            ..status(PeopleJobState::Ready)
        };
        let failed = PeopleJobStatus {
            error: Some(PeopleJobError {
                code: job_error::EXPIRED.into(),
                message: "not ready in time".into(),
            }),
            ..status(PeopleJobState::Failed)
        };
        let other = PeopleJobStatus {
            mine: false,
            ..status(PeopleJobState::Cancelled)
        };
        let head = r#"{"job_id":"5f0c9a1b22d4e7a3","rev":42,"#;
        let tail = r#""account_epoch":3,"created_ms":1794999000000,"updated_ms":1794999100000,"expires_ms":1795020600000}"#;
        let counts = r#""done":120,"total":340,"numbers":812,"#;
        pinned(&[
            (
                running,
                format!(r#"{head}"state":"running","phase":"profiles",{counts}{tail}"#).as_str(),
            ),
            (
                waiting,
                format!(
                    r#"{head}"state":"waiting","phase":"profiles",{counts}"wait":{{"until_ms":1795000000000,"source":"whatsapp"}},"eta_ms":95000,{tail}"#
                )
                .as_str(),
            ),
            (
                ready,
                format!(
                    r#"{head}"state":"ready","phase":"bundle",{counts}"result":{{"records":3,"pictures":1,"bytes":61234}},{tail}"#
                )
                .as_str(),
            ),
            (
                failed,
                format!(
                    r#"{head}"state":"failed","phase":"profiles",{counts}"error":{{"code":"expired","message":"not ready in time"}},{tail}"#
                )
                .as_str(),
            ),
            (
                other,
                format!(r#"{head}"mine":false,"state":"cancelled","phase":"profiles",{counts}{tail}"#)
                    .as_str(),
            ),
        ]);
        for (source, text) in [
            (PeopleWaitSource::Whatsapp, "\"whatsapp\""),
            (PeopleWaitSource::Gateway, "\"gateway\""),
            (PeopleWaitSource::NotConnected, "\"not_connected\""),
        ] {
            assert_eq!(to_json(&source), text);
            assert_eq!(source.as_str(), text.trim_matches('"'));
        }
    }

    #[test]
    fn a_build_that_knows_less_still_reads_the_status() {
        // A newer gateway: unknown state, phase, source and fields.
        let newer: PeopleJobStatus = serde_json::from_str(
            r#"{"job_id":"j","rev":1,"state":"paused","phase":"thinking","wait":{"until_ms":5,"source":"cloud"},"extra":{"x":1}}"#,
        )
        .unwrap();
        assert_eq!(newer.state, PeopleJobState::Other);
        assert_eq!(newer.phase, PeopleJobPhase::Other);
        assert_eq!(newer.wait.unwrap().source, PeopleWaitSource::Other);
        assert!(newer.mine, "mine defaults to true");
        assert_eq!((newer.done, newer.total, newer.numbers), (0, 0, 0));
        // An older client (fewer fields, no deny_unknown_fields) reads a new status.
        #[derive(serde::Deserialize)]
        struct OldStatus {
            job_id: String,
            state: PeopleJobState,
        }
        let text = to_json(&status(PeopleJobState::Ready));
        let old: OldStatus = serde_json::from_str(&text).unwrap();
        assert_eq!(
            (old.job_id.as_str(), old.state),
            ("5f0c9a1b22d4e7a3", PeopleJobState::Ready)
        );
    }

    #[test]
    fn entries_are_accepted_by_the_number_rule() {
        let entry = |n: &str, h: Option<&str>| PeopleEntry {
            n: n.into(),
            h: h.map(str::to_owned),
            p: None,
        };
        let accepted = accept_entries(&[
            entry("+100000000001", Some("aa")),
            entry("100000000002", None),
            entry("+12345", None),
            entry("+1000000000012345", None),
            entry(" +100000000003 ", Some("  ")),
            entry("+100000000001", Some("bb")),
            entry("+10000000000x", None),
        ])
        .unwrap();
        let summary: Vec<(u32, &str, Option<&str>)> = accepted
            .iter()
            .map(|a| (a.seq, a.jid.as_str(), a.hash.as_deref()))
            .collect();
        assert_eq!(
            summary,
            [
                (1, "100000000001@s.whatsapp.net", Some("aa")),
                (2, "100000000003@s.whatsapp.net", None),
            ]
        );
        assert_eq!(accepted[1].number, "+100000000003");
        let too_many = vec![entry("+100000000001", None); MAX_PEOPLE_NUMBERS + 1];
        assert!(accept_entries(&too_many).is_none());
        let long = "x".repeat(MAX_ENTRY_FIELD + 1);
        assert_eq!(
            accept_entries(&[entry("+100000000001", Some(&long))]).unwrap()[0].hash,
            None
        );
    }

    #[test]
    fn the_hash_is_pinned() {
        let input = HashInput {
            number: "+100000000001",
            on_whatsapp: true,
            jid: Some("100000000001@s.whatsapp.net"),
            lid: Some("123456@lid"),
            push_name: Some("Ada"),
            verified_name: None,
            picture_id: Some("1716839201"),
        };
        // The test vector: change it only together with HASH_DOMAIN.
        assert_eq!(people_hash(&input), "396c0369f4e9255e");
        assert_eq!(people_hash(&input).len(), 16);
        // Trimming: the store's names are trimmed; so is the hash input.
        let padded = HashInput {
            push_name: Some("  Ada "),
            ..input
        };
        assert_eq!(people_hash(&padded), people_hash(&input));
        // A negative is hashed too.
        let negative = HashInput {
            number: "+100000000002",
            ..HashInput::default()
        };
        assert_eq!(people_hash(&negative).len(), 16);
        assert_ne!(people_hash(&negative), people_hash(&input));
    }

    #[test]
    fn fields_with_separators_do_not_collide() {
        let base = HashInput {
            number: "+100000000001",
            on_whatsapp: true,
            ..HashInput::default()
        };
        let cases = [
            HashInput {
                push_name: Some("a,b"),
                ..base
            },
            HashInput {
                push_name: Some("a"),
                verified_name: Some("b"),
                ..base
            },
            HashInput {
                push_name: Some("a\nb"),
                ..base
            },
            HashInput {
                push_name: Some("1:a,"),
                ..base
            },
            HashInput {
                verified_name: Some("1:a,"),
                ..base
            },
            HashInput {
                push_name: Some(""),
                verified_name: Some("x"),
                ..base
            },
            HashInput {
                push_name: Some("x"),
                ..base
            },
            base,
            HashInput {
                on_whatsapp: false,
                ..base
            },
        ];
        let hashes: std::collections::HashSet<String> = cases.iter().map(people_hash).collect();
        assert_eq!(hashes.len(), cases.len());
        // An empty name is the same as no name.
        assert_eq!(
            people_hash(&HashInput {
                push_name: Some(""),
                ..base
            }),
            people_hash(&base)
        );
    }

    #[test]
    fn the_start_digest_follows_mode_and_entries() {
        let entries = accept_entries(&[PeopleEntry {
            n: "+100000000001".into(),
            h: Some("aa".into()),
            p: None,
        }])
        .unwrap();
        let auto = start_digest(PeopleSyncMode::Auto, &entries);
        assert_eq!(auto.len(), 64);
        assert_eq!(auto, start_digest(PeopleSyncMode::Auto, &entries));
        assert_ne!(auto, start_digest(PeopleSyncMode::Full, &entries));
        let mut other = entries.clone();
        other[0].picture = Some("aa".into());
        other[0].hash = None;
        assert_ne!(auto, start_digest(PeopleSyncMode::Auto, &other));
    }

    fn record(seq: u32, on: bool) -> PeopleRecord {
        let mut r = PeopleRecord {
            seq,
            number: format!("+10000000000{seq}"),
            on_whatsapp: on,
            jid: on.then(|| format!("10000000000{seq}@s.whatsapp.net")),
            push_name: on.then(|| "Ada\nLovelace".to_owned()),
            ..Default::default()
        };
        r.hash = people_hash(&r.hash_input());
        r
    }

    /// A part read whole: its header, end line and records with their pictures.
    type Read = (
        BundleHeader,
        BundleEnd,
        Vec<(PeopleRecord, Option<Vec<u8>>)>,
    );

    fn read_all(bytes: &[u8]) -> Result<Read, BundleError> {
        let mut seen = Vec::new();
        let (header, end) = read_bundle::<_, BundleError, _>(&mut &bytes[..], |r, p| {
            seen.push((r, p));
            Ok(())
        })?;
        Ok((header, end, seen))
    }

    #[test]
    fn a_bundle_part_is_pinned_and_round_trips() {
        let mut writer = BundleWriter::new("5f0c9a1b22d4e7a3", 0);
        let mut with_picture = record(1, true);
        with_picture.picture_id = Some("1716".into());
        writer.record(&with_picture, Some(b"\n\xff\xd8JPEG\n"));
        writer.record(&record(2, false), None);
        let mut missing = record(3, true);
        missing.picture_id = Some("17".into());
        missing.picture_missing = true;
        writer.record(&missing, None);
        assert_eq!(writer.records(), 3);
        let bytes = writer.finish(false);
        let text = String::from_utf8_lossy(&bytes);
        assert!(
            text.starts_with("{\"v\":1,\"job_id\":\"5f0c9a1b22d4e7a3\",\"after\":0}\n{\"seq\":1,")
        );
        assert!(text.ends_with("{\"end\":true,\"last_seq\":3,\"more\":false}\n"));
        assert!(text.contains(r#""picture_id":"1716","picture_bytes":8}"#));
        assert!(text.contains(r#""picture_id":"17","picture_missing":true}"#));
        let (header, end, records) = read_all(&bytes).unwrap();
        assert_eq!(header.after, 0);
        assert_eq!(
            end,
            BundleEnd {
                end: true,
                last_seq: 3,
                more: false
            }
        );
        assert_eq!(records.len(), 3);
        assert_eq!(records[0].1.as_deref(), Some(&b"\n\xff\xd8JPEG\n"[..]));
        assert_eq!(records[0].0.picture_bytes, Some(8));
        assert_eq!(records[1].0, record(2, false));
        assert!(records[2].0.picture_missing && records[2].1.is_none());
        // An unchanged run: the header and the end line, about a hundred bytes.
        let empty = BundleWriter::new("5f0c9a1b22d4e7a3", 0).finish(false);
        assert_eq!(
            String::from_utf8(empty.clone()).unwrap(),
            "{\"v\":1,\"job_id\":\"5f0c9a1b22d4e7a3\",\"after\":0}\n{\"end\":true,\"last_seq\":0,\"more\":false}\n"
        );
        assert!(read_all(&empty).unwrap().2.is_empty());
    }

    #[test]
    fn a_cut_part_is_an_error_and_the_cursor_pages() {
        let mut writer = BundleWriter::new("j", 5);
        let mut r = record(6, true);
        r.picture_id = Some("1".into());
        writer.record(&r, Some(&[7; 100]));
        writer.record(&record(7, false), None);
        let bytes = writer.finish(true);
        let (_, end, _) = read_all(&bytes).unwrap();
        assert_eq!((end.last_seq, end.more), (7, true));
        for cut in [0, 10, bytes.len() - 120, bytes.len() - 1] {
            let mut seen = 0;
            let result = read_bundle::<_, BundleError, _>(&mut &bytes[..cut], |_, _| {
                seen += 1;
                Ok(())
            });
            assert_eq!(result.unwrap_err(), BundleError::Cut, "cut at {cut}");
        }
        // Garbage and a missing header are bad, not cut.
        assert!(matches!(read_all(b"hello\n"), Err(BundleError::Bad(_))));
        let no_header = b"{\"end\":true,\"last_seq\":0,\"more\":false}\n";
        assert!(matches!(read_all(no_header), Err(BundleError::Bad(_))));
        let future = b"{\"v\":2,\"job_id\":\"j\",\"after\":0}\n";
        assert!(matches!(read_all(future), Err(BundleError::Bad(_))));
    }

    #[test]
    fn a_record_with_more_fields_still_reads() {
        let line = br#"{"v":1,"job_id":"j","after":0}
{"seq":1,"number":"+100000000001","hash":"00","on_whatsapp":false,"about":"x"}
{"end":true,"last_seq":1,"more":false,"parts":3}
"#;
        let (_, end, records) = read_all(line).unwrap();
        assert_eq!(end.last_seq, 1);
        assert_eq!(records[0].0.number, "+100000000001");
    }
}
