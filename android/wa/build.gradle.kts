plugins {
    id("rovsti.android.library")
    id("rovsti.compose")
    id("rovsti.screenshots")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rovsti.wa"
    testFixtures { enable = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Source-scanning tests (WaManifestTest, FakeWaGatewayTest) read src/main: re-run when it changes.
                it.inputs.files(fileTree("src/main")).withPropertyName("mainSources")
                // The wa.link_* request and answer goldens shared with Rust (WaLinkStoreTest).
                val ffiFixtures = rootProject.projectDir.parentFile.resolve("crates/rovsti_ffi/fixtures")
                it.systemProperty("rovsti.ffi.fixtures", ffiFixtures.absolutePath)
                it.inputs.dir(ffiFixtures).withPropertyName("ffiFixtures")
            }
        }
    }
}

dependencies {
    api(project(":core-bridge"))
    implementation(project(":design"))
    // Seams only: WaPush events, WaCallAudio, clipboard, share, files (ARCH 7.4).
    implementation(project(":platform"))
    api(libs.coroutines.core)
    testFixturesApi(project(":core-bridge"))
    testFixturesApi(testFixtures(project(":core-bridge")))
    testFixturesApi(libs.coroutines.core)
    testFixturesImplementation(project(":platform"))
    testFixturesImplementation(project(":design"))
    // The compose compiler plugin runs on testFixtures too and needs the runtime that WaLinkState (@Immutable) uses.
    testFixturesImplementation(platform(libs.compose.bom))
    testFixturesImplementation(libs.compose.ui)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":core-bridge")))
    testImplementation(testFixtures(project))
}
