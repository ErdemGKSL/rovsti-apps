//! The purge of disappearing messages (E6-WA R5, owner decision). A message whose timer ran out is deleted from the
//! store with what hangs on it, and its downloaded media leaves the cache. It runs when the gateway starts (the first
//! tick of the interval is immediate) and every [`PURGE_EVERY`]. Nothing is announced: the apps expire the same
//! messages on their own clock (`wa_sync` `expire`).

use std::time::Duration;

use tokio::task::JoinHandle;

use crate::media_cache::{DEFAULT_MAX_BYTES, MediaCache};
use crate::{AppState, now_ms};

/// How often due messages are purged.
pub const PURGE_EVERY: Duration = Duration::from_secs(5 * 60);

/// Deletes every message that is due (batch after batch) and its cached media. Returns how many went.
pub async fn purge(state: &AppState) -> usize {
    let mut count = 0;
    loop {
        let gone = match state.store().purge_expired_messages(now_ms()).await {
            Ok(gone) => gone,
            Err(e) => {
                log::warn!("purging disappearing messages failed: {e}");
                return count;
            }
        };
        let batch = gone.len();
        count += batch;
        for message in gone {
            let cache = MediaCache::new(
                state.config().media_dir(message.instance.as_str()),
                DEFAULT_MAX_BYTES,
            );
            // Best effort: the cache evicts what is left anyway. Message media is cached by message id.
            let _ = tokio::task::spawn_blocking(move || cache.remove(&message.id)).await;
        }
        if batch < crate::store::PURGE_BATCH {
            break;
        }
    }
    if count > 0 {
        log::info!("disappearing messages purged n={count}");
    }
    count
}

/// Starts the background task that purges due messages now and every `every`.
pub fn spawn_purger(state: AppState, every: Duration) -> JoinHandle<()> {
    tokio::spawn(async move {
        let mut tick = tokio::time::interval(every);
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tick.tick().await;
            purge(&state).await;
        }
    })
}
