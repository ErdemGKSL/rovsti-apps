//! The JNI seam between Rust and `dev.rovsti.bridge.RovstiBridge`.
//!
//! `gpui_android` keeps its `JavaVM` private, so the Kotlin host hands us its
//! own: `RovstiBridge.attachNative()` (called from `MainActivity.onCreate`)
//! invokes the `external fun nativeInit` (via `attachNative()`, once the activity has loaded the library), implemented below. After that Rust
//! calls `RovstiBridge`'s static methods with plain strings and ints, which
//! keeps the JNI surface small and the Android logic in Kotlin.

#[cfg(target_os = "android")]
pub use android::*;

#[cfg(target_os = "android")]
mod android {
    use std::{
        panic::{AssertUnwindSafe, catch_unwind},
        sync::OnceLock,
    };

    use jni::{
        JNIEnv, JavaVM,
        objects::{GlobalRef, JClass, JObject, JString, JValue},
    };

    use super::Arg;
    use crate::{Error, Result};

    struct Bridge {
        vm: JavaVM,
        /// The bridge class, resolved on a Java thread: `FindClass` from a
        /// Rust-spawned thread only sees the system class loader.
        class: GlobalRef,
    }

    static BRIDGE: OnceLock<Bridge> = OnceLock::new();

    /// Runs a JNI entry point's body. A panic cannot unwind across `extern "system"`:
    /// it would abort the whole process (every service in it, an InCallService
    /// included), so it is caught and logged here.
    fn guarded(entry: &str, f: impl FnOnce()) {
        if catch_unwind(AssertUnwindSafe(f)).is_err() {
            log::error!("{entry} panicked");
        }
    }

    /// `RovstiBridge.nativeInit()`; called on the main thread by every new activity.
    /// Only the first call keeps its VM and class (both are process-wide).
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_rovsti_bridge_RovstiBridge_nativeInit(
        env: JNIEnv<'_>,
        class: JClass<'_>,
    ) {
        guarded("RovstiBridge.nativeInit", || {
            if BRIDGE.get().is_some() {
                return;
            }
            let result = (|| -> Result<()> {
                let vm = env.get_java_vm()?;
                let class = env.new_global_ref(class)?;
                let _ = BRIDGE.set(Bridge { vm, class });
                Ok(())
            })();
            if let Err(error) = result {
                log::error!("RovstiBridge.nativeInit failed: {error}");
            }
        });
    }

    /// `RovstiBridge.nativeOnEvent(kind, json)`; only enqueues (AD-5). No GPUI
    /// type is touched here; events with no sink are dropped.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_rovsti_bridge_RovstiBridge_nativeOnEvent(
        mut env: JNIEnv<'_>,
        _class: JClass<'_>,
        kind: JString<'_>,
        json: JString<'_>,
    ) {
        guarded("RovstiBridge.nativeOnEvent", || {
            let read = |env: &mut JNIEnv<'_>, s: &JString<'_>| -> String {
                env.get_string(s).map(String::from).unwrap_or_default()
            };
            let kind = read(&mut env, &kind);
            let json = read(&mut env, &json);
            crate::events::dispatch(kind, json);
        });
    }

    /// Runs `f` with an attached `JNIEnv` and the bridge class. Local
    /// references created by `f` are released when it returns, so calls from
    /// long-lived native threads do not leak.
    pub fn with_bridge<T>(f: impl FnOnce(&mut JNIEnv<'_>, &JClass<'_>) -> Result<T>) -> Result<T> {
        let bridge = BRIDGE.get().ok_or(Error::NotInitialized)?;
        let mut env = bridge.vm.attach_current_thread()?;
        // SAFETY: the global ref was created from a `jclass` in `nativeInit`
        // and is never released, so it stays a valid class reference.
        let class = unsafe { JClass::from_raw(bridge.class.as_obj().as_raw()) };
        let result = env.with_local_frame(16, |env| f(env, &class));
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
        result
    }

    fn descriptor(args: &[Arg<'_>], ret: &str) -> String {
        let mut d = String::with_capacity(2 + args.len() * 18 + ret.len());
        d.push('(');
        for arg in args {
            d.push_str(match arg {
                Arg::Str(_) => "Ljava/lang/String;",
                Arg::Int(_) => "I",
                Arg::Bool(_) => "Z",
            });
        }
        d.push(')');
        d.push_str(ret);
        d
    }

    fn invoke<'a>(
        env: &mut JNIEnv<'a>,
        class: &JClass<'_>,
        name: &str,
        args: &[Arg<'_>],
        ret: &str,
    ) -> Result<jni::objects::JValueOwned<'a>> {
        // Strings must outlive the call; keep them in a small vector.
        let mut strings: Vec<JObject<'a>> = Vec::new();
        for arg in args {
            if let Arg::Str(s) = arg {
                strings.push(JObject::from(env.new_string(s)?));
            }
        }
        let mut next = 0;
        let values: Vec<JValue<'_, '_>> = args
            .iter()
            .map(|arg| match arg {
                Arg::Str(_) => {
                    let v = JValue::Object(&strings[next]);
                    next += 1;
                    v
                }
                Arg::Int(i) => JValue::Int(*i),
                Arg::Bool(b) => JValue::Bool(u8::from(*b)),
            })
            .collect();
        Ok(env.call_static_method(class, name, descriptor(args, ret), &values)?)
    }

    /// Calls `static boolean RovstiBridge.<name>(args)`.
    pub fn call_bool(name: &str, args: &[Arg<'_>]) -> Result<bool> {
        with_bridge(|env, class| Ok(invoke(env, class, name, args, "Z")?.z()?))
    }

    /// Calls `static int RovstiBridge.<name>(args)`.
    pub fn call_int(name: &str, args: &[Arg<'_>]) -> Result<i32> {
        with_bridge(|env, class| Ok(invoke(env, class, name, args, "I")?.i()?))
    }

    /// Calls `static String RovstiBridge.<name>(args)`; a null result is `""`.
    pub fn call_string(name: &str, args: &[Arg<'_>]) -> Result<String> {
        with_bridge(|env, class| {
            let object = invoke(env, class, name, args, "Ljava/lang/String;")?.l()?;
            if object.is_null() {
                return Ok(String::new());
            }
            let text: String = env.get_string(&JString::from(object))?.into();
            Ok(text)
        })
    }
}

/// Keeps the exported JNI symbols in the final `.so` (spike S1): the linker
/// drops unreferenced `#[no_mangle]` functions from an rlib otherwise. Cheap
/// and idempotent; the apps call it once at start-up. A no-op on desktop.
pub fn keep_symbols() {
    #[cfg(target_os = "android")]
    {
        let init: extern "system" fn(jni::JNIEnv<'_>, jni::objects::JClass<'_>) =
            android::Java_dev_rovsti_bridge_RovstiBridge_nativeInit;
        let on_event: extern "system" fn(
            jni::JNIEnv<'_>,
            jni::objects::JClass<'_>,
            jni::objects::JString<'_>,
            jni::objects::JString<'_>,
        ) = android::Java_dev_rovsti_bridge_RovstiBridge_nativeOnEvent;
        std::hint::black_box((init, on_event));
    }
}

/// One argument of a bridge call (String, Int or Boolean only, AD-5).
#[derive(Clone, Copy, Debug)]
pub enum Arg<'a> {
    Str(&'a str),
    Int(i32),
    Bool(bool),
}

/// Calls `static boolean RovstiBridge.<name>(String)`.
pub fn call_bool_str(name: &str, arg: &str) -> crate::Result<bool> {
    call_bool(name, &[Arg::Str(arg)])
}

/// Calls `static boolean RovstiBridge.<name>()`.
pub fn call_bool0(name: &str) -> crate::Result<bool> {
    call_bool(name, &[])
}

/// Desktop builds have no bridge; callers get [`crate::Error::Unsupported`].
#[cfg(not(target_os = "android"))]
pub fn call_bool(_name: &str, _args: &[Arg<'_>]) -> crate::Result<bool> {
    Err(crate::Error::Unsupported)
}

#[cfg(not(target_os = "android"))]
pub fn call_int(_name: &str, _args: &[Arg<'_>]) -> crate::Result<i32> {
    Err(crate::Error::Unsupported)
}

#[cfg(not(target_os = "android"))]
pub fn call_string(_name: &str, _args: &[Arg<'_>]) -> crate::Result<String> {
    Err(crate::Error::Unsupported)
}
