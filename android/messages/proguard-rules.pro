# R8 rules for this app (release). Library rules come in through consumer rules: :core-bridge keeps the JNI
# surface (RustNative, RustEvents.onEvent); kotlinx.serialization, Compose, Coil and Navigation ship their own.
# Manifest components are kept by AAPT.

# Readable crash stack traces from the field: keep line numbers, hide the source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Reached by name (E6.4s): WorkManager creates the outbox worker by reflection from the class name stored in its
# database (a queued job survives an app update), so the class and its constructor must survive a release build.
-keep class dev.rosty.messages.wa.WaOutboxWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
