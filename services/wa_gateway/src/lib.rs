#![cfg_attr(test, allow(clippy::unwrap_used))]

//! The Rovsti WhatsApp gateway: owns every WhatsApp session and serves the
//! apps over HTTP and WebSocket. See `_bmad-output/plans/wa-gateway.md`.

pub mod auth;
pub mod config;
pub mod driver;
pub mod error;
pub mod hub;
pub mod media_cache;
pub mod push;
pub mod routes;
pub mod store;
pub mod wa;

use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

use rovsti_wa_proto::InstanceState;
use rovsti_wa_proto::limit::AuthLimiter;

use crate::config::Config;
use crate::driver::{EventSink, WaDriver};
use crate::hub::Hub;
use crate::push::{NoopPusher, Pusher};
use crate::store::{InstanceId, Store};

/// Failed authentications one address may make in a burst, and how fast the
/// allowance comes back (per second).
const AUTH_BURST: u32 = 10;
const AUTH_REFILL_PER_SEC: f64 = 0.2;
const AUTH_TRACKED_IPS: usize = 4096;

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
        )
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
