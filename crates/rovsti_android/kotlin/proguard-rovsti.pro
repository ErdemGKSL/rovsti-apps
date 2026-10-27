# Rust calls these by name over JNI.
-keep class dev.rovsti.bridge.RovstiBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }
