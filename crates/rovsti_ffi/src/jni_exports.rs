//! The eight JNI exports of `dev.rovsti.core.RustNative` (architecture 6.3).
//!
//! Each is a thin wrapper: read the arguments (a null or unreadable string is `""`), run
//! the work under the panic guard, write the answer. They are compiled on every target.
//! `RustNative` is a Kotlin `object`, so the second parameter is the instance (`JObject`).

use crate::events::{self, EventSink, JniSink};
use crate::guard::{guarded, or_fallback};
use crate::{handles, ops, state};
use jni::JNIEnv;
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jbyteArray, jint, jlong, jstring};
use std::ptr::null_mut;
use std::sync::Arc;

fn read_string(env: &mut JNIEnv, value: &JString) -> String {
    if value.is_null() {
        return String::new();
    }
    env.get_string(value).map(String::from).unwrap_or_default()
}

fn read_bytes(env: &JNIEnv, value: &JByteArray) -> Vec<u8> {
    if value.is_null() {
        return Vec::new();
    }
    env.convert_byte_array(value).unwrap_or_default()
}

fn write_bytes(env: &JNIEnv, bytes: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(bytes)
        .map_or(null_mut(), |array| array.into_raw())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rovsti_core_RustNative_init<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    data_dir: JString<'l>,
    cache_dir: JString<'l>,
    app_id: JString<'l>,
    sdk: jint,
) -> jbyteArray {
    or_fallback(null_mut(), || {
        let data_dir = read_string(&mut env, &data_dir);
        let cache_dir = read_string(&mut env, &cache_dir);
        let app_id = read_string(&mut env, &app_id);
        let bytes = guarded(|| state::init(&data_dir, &cache_dir, &app_id, sdk));
        write_bytes(&env, &bytes)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rovsti_core_RustNative_call<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    op: JString<'l>,
    request: JByteArray<'l>,
) -> jbyteArray {
    or_fallback(null_mut(), || {
        let op = read_string(&mut env, &op);
        let request = read_bytes(&env, &request);
        write_bytes(&env, &ops::call(&op, &request))
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rovsti_core_RustNative_headless<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    kind: JString<'l>,
    json: JString<'l>,
) -> jstring {
    or_fallback(null_mut(), || {
        let kind = read_string(&mut env, &kind);
        let json = read_string(&mut env, &json);
        let answer = rovsti_app_core::headless::run_guarded(
            &kind,
            &json,
            rovsti_app_core::headless::handle_any,
        );
        env.new_string(answer)
            .map_or(null_mut(), |string| string.into_raw())
    })
}

/// One binary map tile of a `tiles.open` handle (`docs/kotlin-ui/tile-buffer.md`): the `RVT1` buffer, or an error
/// envelope (JSON, starts with `{`). `flags` bit 0 asks for the dark style, bit 1 for the cache only.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rovsti_core_RustNative_tile<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
    z: jint,
    x: jint,
    y: jint,
    flags: jint,
) -> jbyteArray {
    or_fallback(null_mut(), || {
        write_bytes(&env, &ops::tile_export(handle, z, x, y, flags))
    })
}

/// One microphone frame (1 920 bytes of little-endian 16-bit PCM, 60 ms at 16 kHz) for the `wa.call_audio_open`
/// handle `call`. Any other size, a full queue or a closed call drops the frame.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rovsti_core_RustNative_audioPush<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    call: jlong,
    pcm: JByteArray<'l>,
) {
    or_fallback((), || {
        let pcm = read_bytes(&env, &pcm);
        ops::audio_push(call, &pcm);
    });
}

/// Fills `out` with the next frame of the peer's voice. Answers 1 920 (a frame), 0 (none ready yet) or -1 (the
/// call ended or the handle is gone).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rovsti_core_RustNative_audioPull<'l>(
    env: JNIEnv<'l>,
    _this: JObject<'l>,
    call: jlong,
    out: JByteArray<'l>,
) -> jint {
    or_fallback(0, || {
        let Ok(len) = env.get_array_length(&out) else {
            return 0;
        };
        let mut frame = vec![0u8; usize::try_from(len).unwrap_or(0)];
        let pulled = ops::audio_pull(call, &mut frame);
        if pulled > 0 {
            let signed: Vec<i8> = frame[..pulled as usize]
                .iter()
                .map(|&byte| byte as i8)
                .collect();
            if env.set_byte_array_region(&out, 0, &signed).is_err() {
                return 0;
            }
        }
        pulled
    })
}

/// Registers the Kotlin `RustEvents` object (`null` clears it).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rovsti_core_RustNative_setEventSink<'l>(
    mut env: JNIEnv<'l>,
    _this: JObject<'l>,
    sink: JObject<'l>,
) {
    or_fallback((), || {
        if sink.is_null() {
            events::set_sink(None);
            return;
        }
        match JniSink::new(&mut env, &sink) {
            Ok(sink) => events::set_sink(Some(Arc::new(sink) as Arc<dyn EventSink>)),
            Err(error) => log::error!("setEventSink failed: {error}"),
        }
    });
}

/// Drops a handle; unknown or already released ids are ignored.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_rovsti_core_RustNative_release<'l>(
    _env: JNIEnv<'l>,
    _this: JObject<'l>,
    handle: jlong,
) {
    or_fallback((), || {
        handles::global().release(handle);
    });
}
