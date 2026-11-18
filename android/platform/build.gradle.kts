plugins {
    id("rosty.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rosty.platform"
    // R8 rules for what this module reaches by name (WaPush reads the FCM token by reflection).
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    testFixtures { enable = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Source-scanning tests (PlatformConventionTest, PrefsNamesTest) read src/main: re-run when it changes.
                it.inputs.files(fileTree("src/main")).withPropertyName("mainSources")
                it.systemProperty(
                    "rosty.wire.fixtures",
                    rootProject.projectDir.parentFile
                        .resolve("crates/rosty_core/tests/fixtures")
                        .absolutePath,
                )
                it.inputs
                    .dir(
                        rootProject.projectDir.parentFile.resolve("crates/rosty_core/tests/fixtures"),
                    ).withPropertyName("wireFixtures")
                // The headless goldens shared with Rust (HeadlessFixturesTest).
                val ffiFixtures = rootProject.projectDir.parentFile.resolve("crates/rosty_ffi/fixtures")
                it.systemProperty("rosty.ffi.fixtures", ffiFixtures.absolutePath)
                it.inputs.dir(ffiFixtures).withPropertyName("ffiFixtures")
            }
        }
    }
}

dependencies {
    api(project(":core-bridge"))
    implementation(project(":design"))
    api(libs.androidx.activity)
    // The WhatsApp vault keys (plans/wa-vault.md 3, 13.2): BiometricPrompt with a CryptoObject over a FragmentActivity.
    api(libs.androidx.biometric)
    api(libs.androidx.fragment)
    testFixturesApi(project(":core-bridge"))
    testFixturesApi(libs.coroutines.core)
    testFixturesImplementation(libs.robolectric)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":core-bridge")))
    testImplementation(testFixtures(project))
    // The android.jar org.json is a stub on the plain JVM (WireFixturesTest): use the real one.
    testImplementation(libs.org.json)
}
