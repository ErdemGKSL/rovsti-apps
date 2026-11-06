import java.util.Properties
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.w3c.dom.Element

// Optional FCM wake for WhatsApp calls (AD-WA6). Everything Firebase needs lives behind the
// existence of app/google-services.json: without the file nothing below changes the build.
buildscript {
    if (file("google-services.json").exists()) {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.google.gms:google-services:4.4.2") }
    }
}

plugins { id("com.android.application") }

val firebaseEnabled = file("google-services.json").exists()

val gpuiAbis = providers.gradleProperty("gpuiAbis").getOrElse("arm64-v8a,x86_64")
    .split(',').map { it.trim() }.distinct()
require(gpuiAbis.isNotEmpty() && gpuiAbis.all { it == "arm64-v8a" || it == "x86_64" }) {
    "Supported Android ABIs: arm64-v8a, x86_64"
}

val gpuiRustProfile = providers.gradleProperty("gpuiRustProfile")
    .orElse(providers.environmentVariable("GPUIFORGE_RUST_PROFILE"))
val gpuiStagingRoot = providers.gradleProperty("gpuiStagingRoot")
    .orElse(providers.environmentVariable("GPUIFORGE_STAGING_ROOT"))
    .getOrElse("")
val gpuiSigningMode = providers.gradleProperty("gpuiSigning")
    .orElse(providers.environmentVariable("GPUIFORGE_SIGNING_MODE")).getOrElse("auto")
require(gpuiSigningMode in listOf("auto", "debug", "configured", "unsigned")) {
    "Unsupported signing mode: $gpuiSigningMode"
}

android {
    namespace = "dev.rovsti.phone"
    // Android lint's release "vital" pass crashes inside a detector on this
    // toolchain (LintDriver.handleDetectorError); the build does not need it.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    ndkPath = providers.environmentVariable("ANDROID_NDK_HOME").orNull
    ndkPath?.let { path ->
        val properties = Properties()
        file("$path/source.properties").inputStream().use { properties.load(it) }
        ndkVersion = properties.getProperty("Pkg.Revision")
    }
    compileSdk {
        version = release(rootProject.extra["gpuiCompileSdkMajor"] as Int) {
            minorApiLevel = rootProject.extra["gpuiCompileSdkMinor"] as Int
        }
    }
    defaultConfig {
        applicationId = "dev.rovsti.phone"
        minSdk = "34".toInt()
        targetSdk = "36".toInt()
        versionCode = "1".toInt()
        versionName = "0.1.0"
        ndk { abiFilters += gpuiAbis }
    }
    val configuredSigning = if (providers.environmentVariable("GPUIFORGE_SIGNING_ENABLED").orNull == "1") {
        signingConfigs.create("gpuiRelease") {
            storeFile = file(providers.environmentVariable("GPUIFORGE_SIGNING_KEYSTORE").get())
            keyAlias = providers.environmentVariable("GPUIFORGE_SIGNING_KEY_ALIAS").get()
            storePassword = providers.environmentVariable("GPUIFORGE_SIGNING_STORE_PASSWORD").get()
            keyPassword = providers.environmentVariable("GPUIFORGE_SIGNING_KEY_PASSWORD").get()
        }
    } else null
    // The perf build (scripts/perf-build.sh) is the debug type signed with the debug key. It gets
    // its own application ID so it can sit next to the signed release on the same phone.
    buildTypes.getByName("debug") { applicationIdSuffix = ".perf" }
    buildTypes.configureEach {
        when (gpuiSigningMode) {
            "debug" -> signingConfig = signingConfigs.getByName("debug")
            "configured" -> signingConfig = requireNotNull(configuredSigning) {
                "Configured signing requires GPUiForge signing credentials"
            }
            "unsigned" -> signingConfig = null
            "auto" -> if (name == "release") signingConfig = configuredSigning
        }
    }
}

// apps/phone, found from the Gradle project (platforms/android) so the repo can move.
val phoneAppDir = rootProject.projectDir.resolve("../..").canonicalFile

// The shared Kotlin bridge lives in crates/rovsti_android. It is added as a source directory
// instead of a symlink, which Windows checkouts do not create (core.symlinks=false).
android.sourceSets["main"].kotlin.directories.add(
    phoneAppDir.resolve("../../crates/rovsti_android/kotlin").canonicalPath,
)

for (profile in listOf("debug", "release")) {
    val variant = profile.replaceFirstChar { it.uppercase() }
    val rustProfile = gpuiRustProfile.getOrElse(if (profile == "debug") "dev" else "release")
    require(rustProfile.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid Rust profile: $rustProfile" }
    val output = layout.buildDirectory.dir("rustJniLibs/$profile/$rustProfile")
    android.sourceSets[profile].jniLibs.directories.add(output.get().asFile.absolutePath)
    val rustBuilds = gpuiAbis.map { abi ->
        tasks.register<Exec>("buildRust${variant}_${abi.replace('-', '_')}") {
            workingDir(phoneAppDir)
            commandLine(providers.gradleProperty("gpuiForgeExecutable").getOrElse("gpuiforge"),
                "build-android-native", "--output", output.get().asFile.absolutePath,
                "--manifest-path", phoneAppDir.resolve("Cargo.toml").path,
                "--library", "rovsti_phone", "--abi", abi,
                "--profile", rustProfile, "--min-sdk", "34")
            if (gpuiStagingRoot.isNotEmpty()) args("--staging-root", gpuiStagingRoot)
        }
    }
    tasks.matching { it.name == "pre${variant}Build" }.configureEach { dependsOn(rustBuilds) }
}

if (firebaseEnabled) {
    apply(plugin = "com.google.gms.google-services")
    // RovstiMessagingService and its MESSAGING_EVENT manifest entry only exist in this build.
    android.sourceSets.getByName("main").java.directories.add(file("src/firebase/kotlin").absolutePath)
    for (type in listOf("debug", "release")) {
        android.sourceSets.getByName(type).manifest.srcFile(file("src/firebase/AndroidManifest.xml"))
    }
}

dependencies {
    implementation(project(":host"))
    if (firebaseEnabled) implementation("com.google.firebase:firebase-messaging:24.1.0")
    // JVM unit tests (WireFixturesTest): the android.jar org.json is a stub, so use the real one.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}


