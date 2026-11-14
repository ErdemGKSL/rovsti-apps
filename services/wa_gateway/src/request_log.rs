//! One log line per request: `instance=<id> <METHOD> <route template> ->
//! <status> <ms>ms`. The route template comes from [`MatchedPath`], so no
//! JID, phone number or query string reaches the log.

use std::sync::{Arc, OnceLock};
use std::time::Instant;

use axum::extract::{MatchedPath, Request};
use axum::middleware::Next;
use axum::response::Response;
use rovsti_wa_proto::LimitSource;
use rovsti_wa_proto::api::routes as r;

use crate::store::InstanceId;

/// Filled by [`crate::auth::Authed`] once the request's key resolved, so the
/// log line can name the instance.
#[derive(Clone, Debug, Default)]
pub struct RequestInstance(Arc<OnceLock<InstanceId>>);

impl RequestInstance {
    pub fn set(&self, instance: &InstanceId) {
        let _ = self.0.set(instance.clone());
    }

    fn get(&self) -> Option<&InstanceId> {
        self.0.get()
    }
}

/// The name a [`LimitSource`] has on the wire and in the log.
pub fn source_name(source: LimitSource) -> &'static str {
    match source {
        LimitSource::Whatsapp => "whatsapp",
        LimitSource::Gateway => "gateway",
        LimitSource::Other => "other",
    }
}

/// Routes that are polled or held open: logged at DEBUG only.
fn is_quiet(route: &str) -> bool {
    route == r::HEALTHZ || route == r::EVENTS
}

/// The request line, without anything the client put in the path.
pub fn line(instance: Option<&str>, method: &str, route: &str, status: u16, ms: u128) -> String {
    format!(
        "instance={} {method} {route} -> {status} {ms}ms",
        instance.unwrap_or("-")
    )
}

/// The `axum::middleware::from_fn` layer that writes the request line.
pub async fn log_request(mut req: Request, next: Next) -> Response {
    let started = Instant::now();
    let method = req.method().clone();
    let route = req
        .extensions()
        .get::<MatchedPath>()
        .map_or_else(|| "<unmatched>".to_owned(), |p| p.as_str().to_owned());
    let slot = RequestInstance::default();
    req.extensions_mut().insert(slot.clone());
    let response = next.run(req).await;
    let level = if is_quiet(&route) {
        log::Level::Debug
    } else {
        log::Level::Info
    };
    if log::log_enabled!(level) {
        let text = line(
            slot.get().map(InstanceId::as_str),
            method.as_str(),
            &route,
            response.status().as_u16(),
            started.elapsed().as_millis(),
        );
        log::log!(level, "{text}");
    }
    response
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_line_names_the_template_not_the_jid() {
        let text = line(Some("inst1"), "GET", r::PICTURE, 429, 12);
        assert_eq!(
            text,
            format!("instance=inst1 GET {} -> 429 12ms", r::PICTURE)
        );
        assert_eq!(
            line(None, "POST", r::INSTANCES, 201, 3),
            format!("instance=- POST {} -> 201 3ms", r::INSTANCES)
        );
    }

    #[test]
    fn polled_routes_are_quiet() {
        assert!(is_quiet(r::HEALTHZ));
        assert!(is_quiet(r::EVENTS));
        assert!(!is_quiet(r::PROFILES));
        assert_eq!(source_name(LimitSource::Whatsapp), "whatsapp");
        assert_eq!(source_name(LimitSource::Gateway), "gateway");
    }
}
