//! Rust to Kotlin events: one global sink, set by `setEventSink`.

use jni::objects::{GlobalRef, JObject, JValue};
use jni::{JNIEnv, JavaVM};
use serde_json::Value;
use std::sync::{Arc, RwLock};

/// Receives events from any Rust thread. Implementations must keep call order per thread.
pub trait EventSink: Send + Sync {
    fn emit(&self, kind: &str, json: &[u8]);
}

static SINK: RwLock<Option<Arc<dyn EventSink>>> = RwLock::new(None);

/// Replaces the global sink (`None` clears it).
pub fn set_sink(sink: Option<Arc<dyn EventSink>>) {
    *SINK.write().unwrap_or_else(|e| e.into_inner()) = sink;
}

/// Sends `value` as JSON to the sink. `false` when no sink is set or the value cannot be encoded.
pub fn emit(kind: &str, value: &Value) -> bool {
    match serde_json::to_vec(value) {
        Ok(bytes) => emit_raw(kind, &bytes),
        Err(error) => {
            log::warn!("event {kind:?} dropped: {error}");
            false
        }
    }
}

/// Sends already encoded bytes to the sink.
pub fn emit_raw(kind: &str, json: &[u8]) -> bool {
    // Clone the Arc so a slow sink never blocks `set_sink`.
    let sink = SINK.read().unwrap_or_else(|e| e.into_inner()).clone();
    match sink {
        Some(sink) => {
            sink.emit(kind, json);
            true
        }
        None => false,
    }
}

/// Calls `onEvent(String, byte[])` on a Kotlin `RustEvents` object.
pub struct JniSink {
    vm: JavaVM,
    target: GlobalRef,
}

impl JniSink {
    pub fn new(env: &mut JNIEnv, target: &JObject) -> jni::errors::Result<Self> {
        Ok(Self {
            vm: env.get_java_vm()?,
            target: env.new_global_ref(target)?,
        })
    }

    fn deliver(&self, kind: &str, json: &[u8]) -> jni::errors::Result<()> {
        // Daemon: the thread does not keep the JVM alive and detaches when it ends.
        let mut env = self.vm.attach_current_thread_as_daemon()?;
        let kind = env.new_string(kind)?;
        let bytes = env.byte_array_from_slice(json)?;
        let result = env.call_method(
            &self.target,
            "onEvent",
            "(Ljava/lang/String;[B)V",
            &[JValue::Object(&kind), JValue::Object(&bytes)],
        );
        if result.is_err() && env.exception_check().unwrap_or(false) {
            // A throwing listener must not poison later calls on this thread.
            let _ = env.exception_clear();
        }
        result.map(|_| ())
    }
}

impl EventSink for JniSink {
    fn emit(&self, kind: &str, json: &[u8]) {
        if let Err(error) = self.deliver(kind, json) {
            log::warn!("event {kind:?} not delivered: {error}");
        }
    }
}

#[cfg(test)]
pub(crate) mod testing {
    use super::*;
    use std::sync::Mutex;

    /// Serialises tests that touch the global sink.
    pub static LOCK: Mutex<()> = Mutex::new(());

    #[derive(Default)]
    pub struct Fake(pub Mutex<Vec<(String, Vec<u8>)>>);

    impl EventSink for Fake {
        fn emit(&self, kind: &str, json: &[u8]) {
            self.0
                .lock()
                .unwrap()
                .push((kind.to_owned(), json.to_vec()));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::testing::{Fake, LOCK};
    use super::*;
    use serde_json::json;

    #[test]
    fn a_thousand_events_arrive_in_order() {
        let _guard = LOCK.lock().unwrap_or_else(|e| e.into_inner());
        assert!(!emit("early", &json!(0)), "no sink yet");
        let fake = Arc::new(Fake::default());
        set_sink(Some(fake.clone()));
        std::thread::spawn(|| {
            for n in 0..1000 {
                assert!(emit("tick", &json!({ "n": n })));
            }
        })
        .join()
        .unwrap();
        set_sink(None);
        assert!(!emit("late", &json!(0)), "sink cleared");
        let got = fake.0.lock().unwrap();
        assert_eq!(got.len(), 1000);
        for (n, (kind, bytes)) in got.iter().enumerate() {
            assert_eq!(kind, "tick");
            let value: Value = serde_json::from_slice(bytes).unwrap();
            assert_eq!(value["n"], n);
        }
    }
}
