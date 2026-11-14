# R8 rules for this app (release). Library rules come in through consumer rules: :core-bridge keeps the JNI
# surface (RustNative, RustEvents.onEvent); kotlinx.serialization, Compose, Coil and Navigation ship their own.
# Manifest components are kept by AAPT.

# Readable crash stack traces from the field: keep line numbers, hide the source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Reached by name (X.1b). WorkManager creates the worker by reflection from its class name (a periodic request stored in
# its database survives an app update), and the provider is created by the system from the manifest. AAPT keeps the
# provider; the worker's constructor is spelled out here so a rename or a stripped constructor cannot slip through.
-keep class dev.rovsti.contacts.people.PeopleSyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class dev.rovsti.contacts.people.PeopleProvider
