//! FCM HTTP v1 sender: caches the OAuth access token and posts data-only,
//! high-priority messages. All HTTP runs on blocking threads (ureq).

use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use super::jwt::{self, JwtError, ServiceAccount};
use crate::now_ms;

const FCM_BASE: &str = "https://fcm.googleapis.com";
/// `urn:ietf:params:oauth:grant-type:jwt-bearer`, form-encoded.
const JWT_BEARER_GRANT: &str = "urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer";
/// A token is renewed this long before Google says it expires.
const TOKEN_MARGIN_S: u64 = 60;
const HTTP_TIMEOUT: Duration = Duration::from_secs(15);
/// A call that cannot be delivered within this time is not worth delivering.
const MESSAGE_TTL: &str = "30s";

#[derive(Debug, thiserror::Error)]
pub enum FcmError {
    #[error(transparent)]
    Jwt(#[from] JwtError),
    #[error("FCM transport: {0}")]
    Transport(String),
    #[error("OAuth token response: {0}")]
    Token(String),
    #[error("FCM answered {status}: {body}")]
    Rejected { status: u16, body: String },
}

/// What happened to one message.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    Sent,
    /// The device token is dead; drop it.
    Unregistered,
}

/// The `messages:send` request body: data only, so no text can leak into a
/// system notification, with an Android priority and a short TTL.
pub fn message_body(
    token: &str,
    data: &BTreeMap<String, String>,
    high_priority: bool,
) -> serde_json::Value {
    serde_json::json!({
        "message": {
            "token": token,
            "data": data,
            "android": {
                "priority": if high_priority { "HIGH" } else { "NORMAL" },
                "ttl": MESSAGE_TTL,
            },
        }
    })
}

/// Reads FCM's answer. 404 or an `UNREGISTERED` error code means the token is
/// gone for good; any other failure is an error.
pub fn classify(status: u16, body: &str) -> Result<Outcome, FcmError> {
    if (200..300).contains(&status) {
        return Ok(Outcome::Sent);
    }
    if status == 404 || body.contains("UNREGISTERED") {
        return Ok(Outcome::Unregistered);
    }
    Err(FcmError::Rejected {
        status,
        body: body.chars().take(300).collect(),
    })
}

/// An OAuth access token and when to stop using it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CachedToken {
    pub access_token: String,
    pub renew_at_s: u64,
}

impl CachedToken {
    pub fn is_fresh(&self, now_s: u64) -> bool {
        now_s < self.renew_at_s
    }
}

/// Parses Google's token endpoint answer.
pub fn parse_token(body: &str, now_s: u64) -> Result<CachedToken, FcmError> {
    #[derive(serde::Deserialize)]
    struct Reply {
        access_token: String,
        expires_in: u64,
    }
    let reply: Reply = serde_json::from_str(body).map_err(|e| FcmError::Token(e.to_string()))?;
    if reply.access_token.is_empty() {
        return Err(FcmError::Token("empty access token".to_owned()));
    }
    Ok(CachedToken {
        access_token: reply.access_token,
        renew_at_s: now_s + reply.expires_in.saturating_sub(TOKEN_MARGIN_S),
    })
}

fn now_s() -> u64 {
    u64::try_from(now_ms() / 1000).unwrap_or(0)
}

pub struct FcmSender {
    project_id: String,
    account: ServiceAccount,
    agent: ureq::Agent,
    token: Mutex<Option<CachedToken>>,
}

impl std::fmt::Debug for FcmSender {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "FcmSender({})", self.project_id)
    }
}

impl FcmSender {
    /// Checks up front that the key signs, so a bad service account stops the
    /// gateway at boot instead of at the first push.
    pub fn new(project_id: String, account: ServiceAccount) -> Result<Self, FcmError> {
        jwt::assertion(&account, now_s())?;
        let agent = ureq::Agent::config_builder()
            .http_status_as_error(false)
            .timeout_global(Some(HTTP_TIMEOUT))
            .build()
            .into();
        Ok(Self {
            project_id,
            account,
            agent,
            token: Mutex::new(None),
        })
    }

    /// Sends one message. Runs on a blocking thread.
    pub async fn send(
        self: &Arc<Self>,
        device_token: String,
        data: BTreeMap<String, String>,
        high_priority: bool,
    ) -> Result<Outcome, FcmError> {
        let this = Arc::clone(self);
        tokio::task::spawn_blocking(move || this.send_blocking(&device_token, &data, high_priority))
            .await
            .map_err(|e| FcmError::Transport(format!("task failed: {e}")))?
    }

    fn cached_token(&self) -> Option<String> {
        let guard = self.token.lock().unwrap_or_else(|e| e.into_inner());
        guard
            .as_ref()
            .filter(|t| t.is_fresh(now_s()))
            .map(|t| t.access_token.clone())
    }

    fn access_token(&self) -> Result<String, FcmError> {
        if let Some(token) = self.cached_token() {
            return Ok(token);
        }
        let now = now_s();
        let assertion = jwt::assertion(&self.account, now)?;
        let form = format!("grant_type={JWT_BEARER_GRANT}&assertion={assertion}");
        let mut response = self
            .agent
            .post(&self.account.token_uri)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .send(form.as_str())
            .map_err(|e| FcmError::Transport(e.to_string()))?;
        let status = response.status().as_u16();
        let body = response
            .body_mut()
            .read_to_string()
            .map_err(|e| FcmError::Transport(e.to_string()))?;
        if status != 200 {
            return Err(FcmError::Token(format!(
                "status {status}: {}",
                body.chars().take(300).collect::<String>()
            )));
        }
        let token = parse_token(&body, now)?;
        let access = token.access_token.clone();
        *self.token.lock().unwrap_or_else(|e| e.into_inner()) = Some(token);
        Ok(access)
    }

    fn forget_token(&self) {
        *self.token.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }

    fn send_blocking(
        &self,
        device_token: &str,
        data: &BTreeMap<String, String>,
        high_priority: bool,
    ) -> Result<Outcome, FcmError> {
        let access = self.access_token()?;
        let url = format!("{FCM_BASE}/v1/projects/{}/messages:send", self.project_id);
        let mut response = self
            .agent
            .post(&url)
            .header("Authorization", &format!("Bearer {access}"))
            .send_json(message_body(device_token, data, high_priority))
            .map_err(|e| FcmError::Transport(e.to_string()))?;
        let status = response.status().as_u16();
        let body = response.body_mut().read_to_string().unwrap_or_default();
        if status == 401 {
            // The cached token was revoked early; fetch a new one next time.
            self.forget_token();
        }
        classify(status, &body)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn data() -> BTreeMap<String, String> {
        BTreeMap::from([
            ("chat".to_owned(), "123@g.us".to_owned()),
            ("count".to_owned(), "2".to_owned()),
            ("kind".to_owned(), "message".to_owned()),
        ])
    }

    #[test]
    fn request_body_is_pinned() {
        let body = message_body("tok", &data(), true);
        // Compared as JSON values: key order depends on serde_json features
        // other crates in the workspace switch on.
        assert_eq!(
            body,
            serde_json::json!({
                "message": {
                    "token": "tok",
                    "data": {"chat": "123@g.us", "count": "2", "kind": "message"},
                    "android": {"priority": "HIGH", "ttl": "30s"},
                }
            })
        );
        let normal = message_body("tok", &data(), false);
        assert_eq!(normal["message"]["android"]["priority"], "NORMAL");
        // Data only: no `notification` block that the system would display.
        assert!(body["message"].get("notification").is_none());
    }

    #[test]
    fn answers_are_classified() {
        assert_eq!(classify(200, "{}").unwrap(), Outcome::Sent);
        assert_eq!(classify(404, "{}").unwrap(), Outcome::Unregistered);
        let unregistered = r#"{"error":{"code":404,"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}"#;
        assert_eq!(classify(404, unregistered).unwrap(), Outcome::Unregistered);
        assert_eq!(classify(400, unregistered).unwrap(), Outcome::Unregistered);
        let err = classify(503, "unavailable").unwrap_err();
        assert!(matches!(err, FcmError::Rejected { status: 503, .. }));
        assert!(classify(400, "INVALID_ARGUMENT").is_err());
    }

    #[test]
    fn tokens_are_renewed_before_they_expire() {
        let token = parse_token(
            r#"{"access_token":"ya29.x","expires_in":3599,"token_type":"Bearer"}"#,
            1000,
        )
        .unwrap();
        assert_eq!(token.access_token, "ya29.x");
        assert_eq!(token.renew_at_s, 1000 + 3599 - 60);
        assert!(token.is_fresh(1000 + 3538));
        assert!(!token.is_fresh(1000 + 3539));
        assert!(parse_token("{}", 0).is_err());
        assert!(parse_token(r#"{"access_token":"","expires_in":5}"#, 0).is_err());
        // A tiny lifetime never underflows.
        assert_eq!(
            parse_token(r#"{"access_token":"a","expires_in":10}"#, 5)
                .unwrap()
                .renew_at_s,
            5
        );
    }

    #[test]
    fn a_broken_service_account_is_refused_at_construction() {
        let sa =
            ServiceAccount::from_json(br#"{"client_email":"a@b","private_key":"nope"}"#).unwrap();
        assert!(FcmSender::new("p".into(), sa).is_err());
    }
}
