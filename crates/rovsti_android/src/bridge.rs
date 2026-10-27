//! The JNI seam between Rust and `dev.rovsti.bridge.RovstiBridge`.
//!
//! `gpui_android` keeps its `JavaVM` private, so the Kotlin host hands us its
//! own: `RovstiBridge.install(context)` (called from the app's `Application`)
//! invokes the `external fun nativeInit`, implemented below. After that Rust
//! calls `RovstiBridge`'s static methods with plain strings and ints, which
//! keeps the JNI surface small and the Android logic in Kotlin.

#[cfg(target_os = "android")]
pub use android::*;

#[cfg(target_os = "android")]
mod android {
    use std::sync::OnceLock;

    use jni::{
        JNIEnv, JavaVM,
        objects::{GlobalRef, JClass, JObject, JValue},
    };

    use crate::{Error, Result};

    struct Bridge {
        vm: JavaVM,
        /// The bridge class, resolved on a Java thread: `FindClass` from a
        /// Rust-spawned thread only sees the system class loader.
        class: GlobalRef,
    }

    static BRIDGE: OnceLock<Bridge> = OnceLock::new();

    /// `RovstiBridge.nativeInit()`; runs once per process on the main thread.
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_rovsti_bridge_RovstiBridge_nativeInit(
        env: JNIEnv<'_>,
        class: JClass<'_>,
    ) {
        let result = (|| -> Result<()> {
            let vm = env.get_java_vm()?;
            let class = env.new_global_ref(class)?;
            let _ = BRIDGE.set(Bridge { vm, class });
            Ok(())
        })();
        if let Err(error) = result {
            log::error!("RovstiBridge.nativeInit failed: {error}");
        }
    }

    /// Runs `f` with an attached `JNIEnv` and the bridge class.
    pub fn with_bridge<T>(f: impl FnOnce(&mut JNIEnv<'_>, &JClass<'_>) -> Result<T>) -> Result<T> {
        let bridge = BRIDGE.get().ok_or(Error::NotInitialized)?;
        let mut env = bridge.vm.attach_current_thread()?;
        // SAFETY: the global ref was created from a `jclass` in `nativeInit`
        // and is never released, so it stays a valid class reference.
        let class = unsafe { JClass::from_raw(bridge.class.as_obj().as_raw()) };
        let result = f(&mut env, &class);
        if env.exception_check().unwrap_or(false) {
            let _ = env.exception_describe();
            let _ = env.exception_clear();
        }
        result
    }

    /// Calls `static boolean RovstiBridge.<name>(String)`.
    pub fn call_bool_str(name: &str, arg: &str) -> Result<bool> {
        with_bridge(|env, class| {
            let arg = env.new_string(arg)?;
            let value = env.call_static_method(
                class,
                name,
                "(Ljava/lang/String;)Z",
                &[JValue::Object(&JObject::from(arg))],
            )?;
            Ok(value.z()?)
        })
    }

    /// Calls `static boolean RovstiBridge.<name>()`.
    pub fn call_bool(name: &str) -> Result<bool> {
        with_bridge(|env, class| Ok(env.call_static_method(class, name, "()Z", &[])?.z()?))
    }
}

/// Desktop builds have no bridge; callers get [`crate::Error::Unsupported`].
#[cfg(not(target_os = "android"))]
pub fn call_bool_str(_name: &str, _arg: &str) -> crate::Result<bool> {
    Err(crate::Error::Unsupported)
}

#[cfg(not(target_os = "android"))]
pub fn call_bool(_name: &str) -> crate::Result<bool> {
    Err(crate::Error::Unsupported)
}
