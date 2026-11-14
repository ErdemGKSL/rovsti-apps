# R8 rules for this app (release). Library rules come in through consumer rules: :core-bridge keeps the JNI
# surface (RustNative, RustEvents.onEvent); kotlinx.serialization, Compose, Coil and Navigation ship their own.
# Manifest components are kept by AAPT.

# Readable crash stack traces from the field: keep line numbers, hide the source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Reached by name (people sync v2 S11): WorkManager creates the worker by reflection from the class name stored in its
# database, so the class and its constructor must survive a release build unchanged.
-keep class dev.rovsti.phone.people.PhonePeopleSyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
