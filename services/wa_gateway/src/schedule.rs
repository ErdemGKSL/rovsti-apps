//! Sends scheduled texts when they fall due.

use std::collections::HashMap;
use std::time::Duration;

use rovsti_wa_proto::{GatewayEvent, ScheduledState, ScheduledText, SendText};
use tokio::task::JoinHandle;

use crate::store::InstanceId;
use crate::{AppState, now_ms};

/// How long a failed send waits before its one retry.
pub const RETRY_DELAY: Duration = Duration::from_secs(60);
/// Due items handled per tick; the rest wait for the next one.
const BATCH: u32 = 100;
/// Finished items stay listed (and stored) this long.
pub const KEEP_FINISHED_MS: i64 = 7 * 24 * 60 * 60 * 1000;

/// What to do with a due item after a send attempt failed.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Attempt {
    /// First failure: try again at this time.
    RetryAt(i64),
    /// Second failure: give up.
    GiveUp,
}

/// Decides between a retry and giving up; `already_retried` is true for an
/// item that failed once before.
fn after_failure(already_retried: bool, now_ms: i64, delay: Duration) -> Attempt {
    if already_retried {
        Attempt::GiveUp
    } else {
        let delay = i64::try_from(delay.as_millis()).unwrap_or(i64::MAX);
        Attempt::RetryAt(now_ms.saturating_add(delay))
    }
}

/// Starts the background task that sends due items every `every`.
pub fn spawn_sweeper(state: AppState, every: Duration) -> JoinHandle<()> {
    spawn_sweeper_with(state, every, RETRY_DELAY)
}

/// [`spawn_sweeper`] with its own retry delay, for tests.
pub fn spawn_sweeper_with(state: AppState, every: Duration, retry: Duration) -> JoinHandle<()> {
    tokio::spawn(async move {
        let mut retries: HashMap<String, i64> = HashMap::new();
        let mut tick = tokio::time::interval(every);
        tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            tick.tick().await;
            sweep(&state, &mut retries, retry).await;
        }
    })
}

/// One pass: sends every due item whose retry time (if any) has come.
/// `retries` maps an item that failed once to the time of its retry.
async fn sweep(state: &AppState, retries: &mut HashMap<String, i64>, retry: Duration) {
    let now = now_ms();
    let due = match state.store().due_scheduled(now, BATCH).await {
        Ok(due) => due,
        Err(e) => {
            log::error!("scheduled: cannot read due items: {e}");
            return;
        }
    };
    // Forget retries of items that were cancelled or deleted meanwhile.
    retries.retain(|id, _| due.iter().any(|(_, item)| item.id == *id));
    for (instance, item) in due {
        if retries.get(&item.id).is_some_and(|at| *at > now) {
            continue;
        }
        let req = SendText {
            chat: item.chat.clone(),
            text: item.text.clone(),
            quoted_id: None,
        };
        match state.driver().send_text(&instance, req).await {
            Ok(sent) => {
                retries.remove(&item.id);
                finish(
                    state,
                    &instance,
                    item,
                    ScheduledState::Sent {
                        message_id: sent.message_id,
                    },
                )
                .await;
            }
            Err(e) => match after_failure(retries.contains_key(&item.id), now_ms(), retry) {
                Attempt::RetryAt(at) => {
                    log::warn!("scheduled {}: send failed, retrying: {e}", item.id);
                    retries.insert(item.id, at);
                }
                Attempt::GiveUp => {
                    log::warn!("scheduled {}: send failed again: {e}", item.id);
                    retries.remove(&item.id);
                    finish(
                        state,
                        &instance,
                        item,
                        ScheduledState::Failed {
                            reason: e.to_string(),
                        },
                    )
                    .await;
                }
            },
        }
    }
    let cutoff = now.saturating_sub(KEEP_FINISHED_MS);
    if let Err(e) = state.store().prune_scheduled(cutoff).await {
        log::warn!("scheduled: pruning failed: {e}");
    }
}

async fn finish(
    state: &AppState,
    instance: &InstanceId,
    mut item: ScheduledText,
    to: ScheduledState,
) {
    match state
        .store()
        .finish_scheduled(item.id.clone(), to.clone())
        .await
    {
        // Cancelled while the send was in flight: nothing to report.
        Ok(false) => {}
        Ok(true) => {
            item.state = to;
            state
                .hub()
                .publish(instance, now_ms(), GatewayEvent::Scheduled(item));
        }
        Err(e) => log::error!("scheduled {}: cannot store the outcome: {e}", item.id),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn one_retry_then_give_up() {
        let d = Duration::from_secs(60);
        assert_eq!(after_failure(false, 1_000, d), Attempt::RetryAt(61_000));
        assert_eq!(after_failure(true, 1_000, d), Attempt::GiveUp);
        assert_eq!(
            after_failure(false, i64::MAX, d),
            Attempt::RetryAt(i64::MAX)
        );
    }
}
