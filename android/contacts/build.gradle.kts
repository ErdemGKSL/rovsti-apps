plugins {
    id("rovsti.android.app")
    id("rovsti.compose")
    id("rovsti.screenshots")
    alias(libs.plugins.kotlin.serialization)
}

val coreBridge = project(":core-bridge")

android {
    namespace = "dev.rovsti.contacts"

    defaultConfig {
        applicationId = "dev.rovsti.contacts"
        // 100 + the CI build number, so every build installs over the last (docs/kotlin-ui/toolchain.md).
        versionCode = 100 + (providers.environmentVariable("ROVSTI_BUILD_NUMBER").orNull?.toIntOrNull() ?: 0)
        // The GPUI app's Cargo.toml version (apps/contacts/Cargo.toml).
        versionName = "0.1.0"
    }

    lint {
        // The dev.rovsti.bridge receiver and provider come from :platform (ARCH 7.2) and are declared here.
        disable += "NotificationPermission"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // librovsti_ffi can be loaded into one class loader per JVM; the plain and the Robolectric tests that
                // run the real Rust core would otherwise fight over it depending on their order (E4.3).
                it.forkEvery = 1
                // The merged manifest of the debug variant is a test input (ContactsManifestTest, E4.1).
                val merged =
                    layout.buildDirectory.dir("intermediates/merged_manifests/debug/processDebugManifest")
                it.dependsOn("processDebugManifest")
                it.systemProperty(
                    "rovsti.mergedManifest",
                    merged
                        .get()
                        .file("AndroidManifest.xml")
                        .asFile.absolutePath,
                )
                val platformRes = rootProject.projectDir.resolve("platform/src/test/resources/manifest")
                it.systemProperty("rovsti.manifestDir", platformRes.absolutePath)
                it.inputs.dir(platformRes).withPropertyName("manifestExpectations")
                // The GPUI/Rust migration fixtures are copied into src/test/resources/migration; the live source is
                // the Rust crate's, which the test compares with (a stale copy fails).
                val rustFixtures =
                    rootProject.projectDir.parentFile.resolve(
                        "crates/rovsti_app_core/tests/fixtures/dirs",
                    )
                it.systemProperty("rovsti.rustFixtureDirs", rustFixtures.absolutePath)
                it.inputs.dir(rustFixtures).withPropertyName("rustFixtureDirs")
                it.inputs.files(fileTree("src/main")).withPropertyName("mainSources")
                // ContactsMigrationTest runs the real Rust core on the host library that :core-bridge builds (E2.6).
                val hostLib = coreBridge.tasks.named("cargoBuildHost").flatMap { (it as CargoBuildTask).outputDir }
                it.dependsOn(coreBridge.tasks.named("cargoBuildHost"))
                it.systemProperty("rovsti.hostLib", hostLib.get().asFile.absolutePath)
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
    implementation(libs.compose.reorderable)
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
