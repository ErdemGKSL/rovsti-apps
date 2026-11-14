#![cfg_attr(test, allow(clippy::unwrap_used))]

//! The Rovsti WhatsApp gateway: owns every WhatsApp session and serves the
//! apps over HTTP and WebSocket. See `_bmad-output/plans/wa-gateway.md`.

pub mod auth;
pub mod config;
pub mod cooldown;
pub mod driver;
pub mod error;
pub mod hub;
pub mod media_cache;
pub mod push;
pub mod request_log;
pub mod rich_routes;
pub mod routes;
pub mod schedule;
pub mod status_routes;
pub mod store;
pub mod wa;

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use rovsti_wa_proto::LookupCache;
use rovsti_wa_proto::limit::{AuthLimiter, TokenBucket};
use rovsti_wa_proto::{ApiError, InstanceState, LimitSource};

use crate::config::Config;
use crate::cooldown::{Check, Cooldown};
use crate::driver::{EventSink, WaDriver};
use crate::error::HttpError;
use crate::hub::Hub;
use crate::push::{NoopPusher, Pusher};
use crate::store::{InstanceId, Store};
use crate::wa::calls::{CallTable, RING_TIMEOUT};

/// Failed authentications one address may make in a burst, and how fast the
/// allowance comes back (per second).
const AUTH_BURST: u32 = 10;
const AUTH_REFILL_PER_SEC: f64 = 0.2;
const AUTH_TRACKED_IPS: usize = 4096;

/// Lookup answers are kept 24 hours, 4096 per instance.
const LOOKUP_TTL_MS: i64 = 24 * 60 * 60 * 1000;
const LOOKUP_CACHE_ENTRIES: usize = 4096;
/// WhatsApp lookups one instance may ask for in a burst (one token per JID),
/// and how fast the allowance comes back (per second).
const LOOKUP_BURST: u32 = 60;
const LOOKUP_REFILL_PER_SEC: f64 = 1.0;

/// Profile and picture queries one instance may send WhatsApp in a burst,
/// and how fast the allowance comes back (per second). An inbox opening
/// asks for one picture per chat, so the burst is larger than for lookups.
const PROFILE_BURST: u32 = 120;
const PROFILE_REFILL_PER_SEC: f64 = 2.0;

/// What the lookup route remembers about one instance.
struct Lookups {
    cache: LookupCache,
    bucket: TokenBucket,
    /// Profile and picture queries.
    profiles: TokenBucket,
    /// The pause after WhatsApp itself rate-limited the instance; shared by
    /// lookups, profiles and pictures.
    upstream: Cooldown,
}

impl Lookups {
    fn new() -> Self {
        Self {
            cache: LookupCache::new(LOOKUP_CACHE_ENTRIES, LOOKUP_TTL_MS),
            bucket: TokenBucket::new(LOOKUP_BURST, LOOKUP_REFILL_PER_SEC),
            profiles: TokenBucket::new(PROFILE_BURST, PROFILE_REFILL_PER_SEC),
            upstream: Cooldown::default(),
        }
    }

    /// The running WhatsApp pause, announcing its end once.
    fn paused(&mut self, instance: &InstanceId, now: i64) -> Option<Refused> {
        match self.upstream.check(now) {
            Check::Paused { left_ms } => Some(Refused::Cooldown { left_ms }),
            Check::Ended => {
                log::info!("instance={instance} cooldown ended");
                None
            }
            Check::Clear => None,
        }
    }
}

/// Why a contact query was not sent to WhatsApp.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Refused {
    /// Over the gateway's own allowance; tokens are back in `retry_after_ms`.
    Bucket { retry_after_ms: u64 },
    /// WhatsApp rate-limited the instance; the pause ends in `left_ms`.
    Cooldown { left_ms: u64 },
}

impl Refused {
    pub fn source(self) -> LimitSource {
        match self {
            Self::Bucket { .. } => LimitSource::Gateway,
            Self::Cooldown { .. } => LimitSource::Whatsapp,
        }
    }

    pub fn retry_after_ms(self) -> u64 {
        match self {
            Self::Bucket { retry_after_ms } => retry_after_ms,
            Self::Cooldown { left_ms } => left_ms,
        }
    }
}

/// A 429 with its hint, and the WARN line every 429 gets. No JID is logged.
fn rate_limited(
    instance: &InstanceId,
    route: &str,
    source: LimitSource,
    retry_after_ms: u64,
    jids: usize,
    message: &str,
) -> HttpError {
    log::warn!(
        "instance={instance} rate limited source={} route={route} retry_after_ms={retry_after_ms} n={jids}",
        request_log::source_name(source)
    );
    HttpError(ApiError::rate_limited(
        message,
        Some(retry_after_ms),
        source,
    ))
}

pub fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// Shared by every request handler. Cheap to clone.
#[derive(Clone)]
pub struct AppState(Arc<Inner>);

struct Inner {
    config: Config,
    store: Store,
    hub: Arc<Hub>,
    driver: Arc<dyn WaDriver>,
    pusher: Arc<dyn Pusher>,
    limiter: Mutex<AuthLimiter>,
    calls: Mutex<HashMap<InstanceId, Arc<CallTable>>>,
    lookups: Mutex<HashMap<InstanceId, Lookups>>,
}

impl std::fmt::Debug for AppState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("AppState")
    }
}

impl AppState {
    /// An app state that sends no push notifications.
    pub fn new(config: Config, store: Store, driver: Arc<dyn WaDriver>) -> Self {
        Self::with_pusher(config, store, driver, Arc::new(NoopPusher))
    }

    pub fn with_pusher(
        config: Config,
        store: Store,
        driver: Arc<dyn WaDriver>,
        pusher: Arc<dyn Pusher>,
    ) -> Self {
        Self(Arc::new(Inner {
            config,
            store,
            hub: Arc::new(Hub::new()),
            driver,
            pusher,
            limiter: Mutex::new(AuthLimiter::new(
                AUTH_BURST,
                AUTH_REFILL_PER_SEC,
                AUTH_TRACKED_IPS,
            )),
            calls: Mutex::new(HashMap::new()),
            lookups: Mutex::new(HashMap::new()),
        }))
    }

    pub fn config(&self) -> &Config {
        &self.0.config
    }

    pub fn store(&self) -> &Store {
        &self.0.store
    }

    pub fn hub(&self) -> &Arc<Hub> {
        &self.0.hub
    }

    pub fn driver(&self) -> &Arc<dyn WaDriver> {
        &self.0.driver
    }

    pub fn sink(&self, instance: &InstanceId) -> EventSink {
        EventSink::new(
            instance.clone(),
            Arc::clone(&self.0.hub),
            self.0.store.clone(),
            Arc::clone(&self.0.pusher),
            self.calls(instance),
        )
    }

    /// The voice calls of `instance`.
    pub fn calls(&self, instance: &InstanceId) -> Arc<CallTable> {
        let mut tables = self.0.calls.lock().unwrap_or_else(|e| e.into_inner());
        Arc::clone(tables.entry(instance.clone()).or_insert_with(|| {
            CallTable::new(
                instance.clone(),
                Arc::clone(&self.0.hub),
                Arc::clone(&self.0.pusher),
                RING_TIMEOUT,
            )
        }))
    }

    /// Forgets the calls of a deleted instance.
    pub fn forget_calls(&self, instance: &InstanceId) {
        self.0
            .calls
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(instance);
    }

    /// Splits `jids` into cached lookup answers and the JIDs still to ask
    /// WhatsApp for. The misses are charged to the instance's lookup bucket,
    /// all or nothing; nothing is charged (and the misses are refused) while
    /// WhatsApp's pause runs or the instance is over its allowance.
    pub fn lookup_cached(
        &self,
        instance: &InstanceId,
        jids: &[String],
    ) -> Result<(Vec<rovsti_wa_proto::WaUser>, Vec<String>), Refused> {
        let now = now_ms();
        let mut all = self.0.lookups.lock().unwrap_or_else(|e| e.into_inner());
        let entry = all.entry(instance.clone()).or_insert_with(Lookups::new);
        let (hits, misses) = entry.cache.split(jids, now);
        if misses.is_empty() {
            return Ok((hits, misses));
        }
        if let Some(refused) = entry.paused(instance, now) {
            return Err(refused);
        }
        let cost = u32::try_from(misses.len()).unwrap_or(u32::MAX);
        let at = u64::try_from(now).unwrap_or(0);
        if entry.bucket.try_take_n(cost, at) {
            Ok((hits, misses))
        } else {
            Err(Refused::Bucket {
                retry_after_ms: entry.bucket.wait_ms(cost, at),
            })
        }
    }

    /// Remembers what WhatsApp answered for `instance`.
    pub fn lookup_store(&self, instance: &InstanceId, users: &[rovsti_wa_proto::WaUser]) {
        let now = now_ms();
        let mut all = self.0.lookups.lock().unwrap_or_else(|e| e.into_inner());
        let entry = all.entry(instance.clone()).or_insert_with(Lookups::new);
        for user in users {
            entry.cache.put(user.clone(), now);
        }
    }

    /// Charges `n` profile or picture queries to the instance; refused (and
    /// nothing charged) while WhatsApp's pause runs or over the allowance.
    pub fn charge_profiles(&self, instance: &InstanceId, n: usize) -> Result<(), Refused> {
        if n == 0 {
            return Ok(());
        }
        let now = now_ms();
        let mut all = self.0.lookups.lock().unwrap_or_else(|e| e.into_inner());
        let entry = all.entry(instance.clone()).or_insert_with(Lookups::new);
        if let Some(refused) = entry.paused(instance, now) {
            return Err(refused);
        }
        let cost = u32::try_from(n).unwrap_or(u32::MAX);
        let at = u64::try_from(now).unwrap_or(0);
        if entry.profiles.try_take_n(cost, at) {
            Ok(())
        } else {
            Err(Refused::Bucket {
                retry_after_ms: entry.profiles.wait_ms(cost, at),
            })
        }
    }

    /// Records that WhatsApp rate-limited `instance` (its hint in
    /// `retry_after_s`) and starts or extends the pause. Returns the pause.
    pub fn note_upstream_limit(&self, instance: &InstanceId, retry_after_s: Option<u32>) -> u64 {
        let now = now_ms();
        let mut all = self.0.lookups.lock().unwrap_or_else(|e| e.into_inner());
        let entry = all.entry(instance.clone()).or_insert_with(Lookups::new);
        let hint = retry_after_s.map(|s| u64::from(s) * 1000);
        let wait = entry.upstream.note(hint, now);
        log::info!("instance={instance} cooldown started wait_ms={wait}");
        wait
    }

    /// The 429 for a refused contact query on `route` (a route template),
    /// logged at WARN. `jids` is how many JIDs the request asked about.
    pub fn refused(
        &self,
        instance: &InstanceId,
        route: &str,
        refused: Refused,
        jids: usize,
    ) -> HttpError {
        let message = match refused {
            Refused::Bucket { .. } => "too many requests for this instance",
            Refused::Cooldown { .. } => "WhatsApp rate limit reached; waiting it out",
        };
        rate_limited(
            instance,
            route,
            refused.source(),
            refused.retry_after_ms(),
            jids,
            message,
        )
    }

    /// WhatsApp answered 429 to a contact query on `route`: starts the pause
    /// and returns the 429 for the app, logged at WARN.
    pub fn upstream_limited(
        &self,
        instance: &InstanceId,
        route: &str,
        retry_after_s: Option<u32>,
        jids: usize,
    ) -> HttpError {
        let wait = self.note_upstream_limit(instance, retry_after_s);
        rate_limited(
            instance,
            route,
            LimitSource::Whatsapp,
            wait,
            jids,
            "WhatsApp rate limit reached",
        )
    }

    /// Forgets the lookups of a deleted instance.
    pub fn forget_lookups(&self, instance: &InstanceId) {
        self.0
            .lookups
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(instance);
    }

    /// Records a failed authentication from `ip`; false once it is over the
    /// limit.
    pub fn allow_failed_auth(&self, ip: IpAddr) -> bool {
        let now = u64::try_from(now_ms()).unwrap_or(0);
        self.0
            .limiter
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .allow(ip, now)
    }

    /// Starts the driver for every stored instance that is still linked.
    /// Returns how many were started; a failing one is logged and skipped.
    pub async fn restart_instances(&self) -> Result<usize, store::StoreError> {
        let mut started = 0;
        for stored in self.0.store.list_instances().await? {
            if matches!(
                stored.state,
                InstanceState::LoggedOut | InstanceState::Banned
            ) {
                continue;
            }
            let sink = self.sink(&stored.id);
            match self.0.driver.start(stored.id.clone(), None, sink).await {
                Ok(()) => started += 1,
                Err(e) => log::error!("instance {} did not start: {e}", stored.id),
            }
        }
        Ok(started)
    }
}

/// Serves the API on `listener` until the future is dropped or the listener
/// fails.
pub async fn serve(listener: tokio::net::TcpListener, state: AppState) -> std::io::Result<()> {
    let app = routes::router(state).into_make_service_with_connect_info::<SocketAddr>();
    axum::serve(listener, app).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::driver::FakeDriver;
    use crate::store::NewKey;
    use rovsti_wa_proto::{ConnectKey, KeyId};

    #[tokio::test]
    async fn restart_skips_logged_out_instances() {
        let store = Store::open_in_memory().unwrap();
        for n in 1..=3u8 {
            let key = ConnectKey::from_bytes(&[n; 32]);
            let new = NewKey {
                key_id: KeyId::from_bytes(&[n; 8]),
                hash: key.hash(),
                label: "k".into(),
            };
            store
                .create_instance(InstanceId::from_bytes(&[n; 12]), new, 1, 4)
                .await
                .unwrap();
        }
        store
            .set_state(
                InstanceId::from_bytes(&[2; 12]),
                InstanceState::LoggedOut,
                None,
                None,
            )
            .await
            .unwrap();
        let driver = Arc::new(FakeDriver::new());
        let state = AppState::new(
            Config::from_parts(None, &|_| None).unwrap(),
            store,
            driver.clone(),
        );
        assert_eq!(state.restart_instances().await.unwrap(), 2);
        let started = driver.started();
        assert_eq!(started.len(), 2);
        assert!(started.iter().all(|(_, link)| link.is_none()));
        assert!(
            started
                .iter()
                .all(|(id, _)| *id != InstanceId::from_bytes(&[2; 12]))
        );
    }

    #[test]
    fn failed_auth_is_limited() {
        let state = AppState::new(
            Config::from_parts(None, &|_| None).unwrap(),
            Store::open_in_memory().unwrap(),
            Arc::new(FakeDriver::new()),
        );
        let ip: IpAddr = "10.1.1.1".parse().unwrap();
        let allowed = (0..AUTH_BURST)
            .filter(|_| state.allow_failed_auth(ip))
            .count();
        assert_eq!(allowed as u32, AUTH_BURST);
        assert!(!state.allow_failed_auth(ip));
        assert!(state.allow_failed_auth("10.1.1.2".parse().unwrap()));
    }
}
