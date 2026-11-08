# JNI looks these up by name from librovsti_ffi (crates/rovsti_ffi/src/events.rs, jni_exports.rs).
-keep class dev.rovsti.core.RustNative { *; }
-keepclassmembers class dev.rovsti.core.RustEvents { public void onEvent(java.lang.String, byte[]); }
