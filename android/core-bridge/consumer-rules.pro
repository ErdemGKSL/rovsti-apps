# JNI looks these up by name from librosty_ffi (crates/rosty_ffi/src/events.rs, jni_exports.rs).
-keep class dev.rosty.core.RustNative { *; }
-keepclassmembers class dev.rosty.core.RustEvents { public void onEvent(java.lang.String, byte[]); }
