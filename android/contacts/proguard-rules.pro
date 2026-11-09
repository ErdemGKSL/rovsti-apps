# R8 rules for this app (release). Library rules come in through consumer rules: :core-bridge keeps the JNI
# surface (RustNative, RustEvents.onEvent); kotlinx.serialization, Compose, Coil and Navigation ship their own.
# Manifest components are kept by AAPT.

# Readable crash stack traces from the field: keep line numbers, hide the source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
