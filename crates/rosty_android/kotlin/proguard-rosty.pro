# Rust calls these by name over JNI.
-keep class dev.rosty.bridge.RostyBridge { *; }
-keepclasseswithmembernames class * { native <methods>; }
