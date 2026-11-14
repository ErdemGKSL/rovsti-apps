//! A registry of Rust objects that Kotlin holds as `Long` ids.
//!
//! An id is `generation << 32 | slot`. The generation is bumped on release, so a stale id
//! never reaches a newer object that reuses the slot. Id `0` is never issued.

use std::any::Any;
use std::sync::{Mutex, MutexGuard, OnceLock};

type Object = Box<dyn Any + Send>;

struct Slot {
    generation: u32,
    object: Option<Object>,
}

#[derive(Default)]
struct Inner {
    slots: Vec<Slot>,
    free: Vec<u32>,
    live: usize,
}

/// Thread-safe registry; use [`global`] in production, a local one in tests.
#[derive(Default)]
pub struct Registry {
    inner: Mutex<Inner>,
}

const MAX_GENERATION: u32 = 0x7FFF_FFFF;

fn pack(generation: u32, slot: u32) -> i64 {
    (i64::from(generation) << 32) | i64::from(slot)
}

fn unpack(id: i64) -> Option<(u32, u32)> {
    if id <= 0 {
        return None;
    }
    Some(((id >> 32) as u32, (id & 0xFFFF_FFFF) as u32))
}

impl Registry {
    pub const fn new() -> Self {
        Self {
            inner: Mutex::new(Inner {
                slots: Vec::new(),
                free: Vec::new(),
                live: 0,
            }),
        }
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Stores `value` and returns its id.
    pub fn insert<T: Any + Send>(&self, value: T) -> i64 {
        let mut inner = self.lock();
        let object: Object = Box::new(value);
        let slot = match inner.free.pop() {
            Some(slot) => {
                inner.slots[slot as usize].object = Some(object);
                slot
            }
            None => {
                inner.slots.push(Slot {
                    generation: 1,
                    object: Some(object),
                });
                (inner.slots.len() - 1) as u32
            }
        };
        inner.live += 1;
        pack(inner.slots[slot as usize].generation, slot)
    }

    /// Runs `f` on the object when `id` is live and holds a `T`. The registry stays locked
    /// meanwhile, so `f` must not call back into it.
    pub fn with<T: Any + Send, R>(&self, id: i64, f: impl FnOnce(&mut T) -> R) -> Option<R> {
        let (generation, slot) = unpack(id)?;
        let mut inner = self.lock();
        let slot = inner.slots.get_mut(slot as usize)?;
        if slot.generation != generation {
            return None;
        }
        slot.object.as_mut()?.downcast_mut::<T>().map(f)
    }

    /// Drops the object. `false` when the id is unknown or already released.
    pub fn release(&self, id: i64) -> bool {
        let Some((generation, slot_index)) = unpack(id) else {
            return false;
        };
        let removed = {
            let mut inner = self.lock();
            let Some(slot) = inner.slots.get_mut(slot_index as usize) else {
                return false;
            };
            if slot.generation != generation || slot.object.is_none() {
                return false;
            }
            let object = slot.object.take();
            slot.generation = if slot.generation >= MAX_GENERATION {
                1
            } else {
                slot.generation + 1
            };
            inner.free.push(slot_index);
            inner.live -= 1;
            object
        };
        // Dropped outside the lock: a destructor may be slow or use the registry.
        drop(removed);
        true
    }

    /// Number of live objects.
    pub fn len(&self) -> usize {
        self.lock().live
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

/// The process-wide registry behind the `release` export.
pub fn global() -> &'static Registry {
    static GLOBAL: OnceLock<Registry> = OnceLock::new();
    GLOBAL.get_or_init(Registry::new)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn insert_with_release() {
        let registry = Registry::new();
        let id = registry.insert(String::from("a"));
        assert!(id > 0);
        assert_eq!(registry.with(id, |s: &mut String| s.push('b')), Some(()));
        assert_eq!(
            registry.with(id, |s: &mut String| s.clone()),
            Some("ab".into())
        );
        assert_eq!(registry.with(id, |n: &mut u32| *n), None, "wrong type");
        assert!(registry.release(id));
        assert!(!registry.release(id), "double release");
        assert_eq!(registry.with(id, |s: &mut String| s.clone()), None);
        assert!(registry.is_empty());
    }

    #[test]
    fn stale_id_never_reaches_a_reused_slot() {
        let registry = Registry::new();
        let first = registry.insert(1u32);
        assert!(registry.release(first));
        let second = registry.insert(2u32);
        assert_ne!(first, second);
        assert_eq!(registry.with(first, |n: &mut u32| *n), None);
        assert!(!registry.release(first));
        assert_eq!(registry.with(second, |n: &mut u32| *n), Some(2));
    }

    #[test]
    fn invalid_ids_are_refused() {
        let registry = Registry::new();
        for id in [0, -1, i64::MIN, i64::MAX, 42] {
            assert!(!registry.release(id));
            assert_eq!(registry.with(id, |n: &mut u32| *n), None);
        }
    }

    #[test]
    fn ten_thousand_handles_do_not_leak() {
        let registry = Registry::new();
        let witness = Arc::new(());
        let ids: Vec<i64> = (0..10_000)
            .map(|_| registry.insert(Arc::clone(&witness)))
            .collect();
        assert_eq!(registry.len(), 10_000);
        assert_eq!(Arc::strong_count(&witness), 10_001);
        for id in &ids {
            assert!(registry.release(*id));
        }
        assert_eq!(registry.len(), 0);
        assert_eq!(Arc::strong_count(&witness), 1, "objects were dropped");
        for id in &ids {
            assert!(!registry.release(*id), "released id refused");
            assert_eq!(registry.with(*id, |_: &mut Arc<()>| ()), None);
        }
    }

    #[test]
    fn generation_wraps_without_going_negative() {
        let registry = Registry::new();
        registry.insert(0u8);
        registry.lock().slots[0].generation = MAX_GENERATION;
        let id = pack(MAX_GENERATION, 0);
        assert!(id > 0);
        assert_eq!(registry.with(id, |n: &mut u8| *n), Some(0));
        assert!(registry.release(id));
        assert_eq!(registry.insert(1u8), pack(1, 0));
    }
}
