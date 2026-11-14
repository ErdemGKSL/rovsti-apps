plugins {
    id("rosty.android.app")
    id("rosty.compose")
    id("rosty.screenshots")
    alias(libs.plugins.kotlin.serialization)
}

val coreBridge = project(":core-bridge")

android {
    namespace = "dev.rosty.stops"

    defaultConfig {
        applicationId = "dev.rosty.stops"
        // 100 + the CI build number, so every build installs over the last (docs/kotlin-ui/toolchain.md).
        // ROVSTI_BUILD_NUMBER is the pre-rename name, still read when the new one is unset.
        val buildNumber =
            providers.environmentVariable("ROSTY_BUILD_NUMBER").orNull
                ?: providers.environmentVariable("ROVSTI_BUILD_NUMBER").orNull
        versionCode = 100 + (buildNumber?.toIntOrNull() ?: 0)
        // The GPUI app's Cargo.toml version (apps/stops/Cargo.toml).
        versionName = "0.1.0"
    }

    lint {
        // The dev.rosty.bridge receiver and provider come from :platform (ARCH 7.2) and are declared here.
        disable += "NotificationPermission"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // librosty_ffi can be loaded into one class loader per JVM; the plain and the Robolectric tests that
                // run the real Rust core would otherwise fight over it depending on their order (E7.1).
                it.forkEvery = 1
                // Robolectric plus the native Rust core exceed the 512m default test heap.
                it.maxHeapSize = "2g"
                // The merged manifest of the release variant is a test input (StopsManifestTest, E7.1).
                val merged =
                    layout.buildDirectory.dir("intermediates/merged_manifests/release/processReleaseManifest")
                it.dependsOn("processReleaseManifest")
                it.systemProperty(
                    "rosty.mergedManifest",
                    merged
                        .get()
                        .file("AndroidManifest.xml")
                        .asFile.absolutePath,
                )
                val platformRes = rootProject.projectDir.resolve("platform/src/test/resources/manifest")
                it.systemProperty("rosty.manifestDir", platformRes.absolutePath)
                it.inputs.dir(platformRes).withPropertyName("manifestExpectations")
                // The GPUI/Rust migration fixtures are copied into src/test/resources/migration; the live source is
                // the Rust crate's, which the test compares with (a stale copy fails).
                val rustFixtures =
                    rootProject.projectDir.parentFile.resolve(
                        "crates/rosty_app_core/tests/fixtures/dirs",
                    )
                it.systemProperty("rosty.rustFixtureDirs", rustFixtures.absolutePath)
                it.inputs.dir(rustFixtures).withPropertyName("rustFixtureDirs")
                // The old single-destination trip (AD-SX8) sits beside the dirs, in the crate's fixture root.
                it.inputs.file(rustFixtures.resolve("../trip.legacy.json")).withPropertyName("legacyTripFixture")
                it.inputs.files(fileTree("src/main")).withPropertyName("mainSources")
                // StopsMigrationTest runs the real Rust core on the host library that :core-bridge builds (E2.6).
                val hostLib = coreBridge.tasks.named("cargoBuildHost").flatMap { (it as CargoBuildTask).outputDir }
                it.dependsOn(coreBridge.tasks.named("cargoBuildHost"))
                it.systemProperty("rosty.hostLib", hostLib.get().asFile.absolutePath)
                it.systemProperty("java.library.path", hostLib.get().asFile.absolutePath)
            }
        }
    }
}

dependencies {
    // :shell exposes :design, :platform, :wa and :core-bridge to the app (api).
    implementation(project(":shell"))
    implementation(project(":design"))
    implementation(project(":platform"))
    implementation(project(":wa"))
    implementation(project(":core-bridge"))
    implementation(libs.activity.compose)
    implementation(libs.coroutines.core)
    implementation(libs.collections.immutable)
    implementation(libs.navigation3.runtime)

    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(testFixtures(project(":core-bridge")))
    testImplementation(testFixtures(project(":platform")))
    testImplementation(testFixtures(project(":wa")))
    testImplementation(testFixtures(project(":shell")))
}
