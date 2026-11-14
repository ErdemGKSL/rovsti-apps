plugins {
    id("rovsti.android.app")
    id("rovsti.compose")
    id("rovsti.screenshots")
    alias(libs.plugins.kotlin.serialization)
}

// FCM for WhatsApp calls: the owner's google-services.json (git-ignored, never committed) sits next to this file. With
// it the google-services plugin generates the Firebase options; without it the firebase flavor builds with no Firebase
// app and WaPush.token() returns "".
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

val coreBridge = project(":core-bridge")

android {
    namespace = "dev.rovsti.phone"

    defaultConfig {
        applicationId = "dev.rovsti.phone"
        // 100 + the CI build number, so every build installs over the last (docs/kotlin-ui/toolchain.md).
        versionCode = 100 + (providers.environmentVariable("ROVSTI_BUILD_NUMBER").orNull?.toIntOrNull() ?: 0)
        // The GPUI app's version (apps/phone/platforms/android/app/build.gradle.kts).
        versionName = "0.1.0"
    }

    // `plain` has no Firebase at all (WaPush reads the token by reflection and gets ""); `firebase` adds the FCM wake
    // service for WhatsApp calls (AD-WA6). The GPUI build turned it on by the presence of google-services.json; the
    // flavor is the explicit switch, and the google-services plugin is applied only when that file exists.
    flavorDimensions += "push"
    productFlavors {
        create("plain") { dimension = "push" }
        create("firebase") { dimension = "push" }
    }

    lint {
        // The dev.rovsti.bridge receivers and provider come from :platform (ARCH 7.2) and are declared here.
        disable += "NotificationPermission"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // librovsti_ffi can be loaded into one class loader per JVM (see :contacts).
                it.forkEvery = 1
                // The merged manifest of this test's flavor is a test input (PhoneManifestTest, E5.1).
                val flavor = if (it.name.contains("Firebase")) "firebase" else "plain"
                val cap = flavor.replaceFirstChar { c -> c.uppercase() }
                val merged =
                    layout.buildDirectory.dir(
                        "intermediates/merged_manifests/${flavor}Release/process${cap}ReleaseManifest",
                    )
                it.dependsOn("process${cap}ReleaseManifest")
                it.systemProperty("rovsti.flavor", flavor)
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
                // The GPUI manifest is the other expectation: every component the GPUI app declared must survive.
                val gpuiManifest =
                    rootProject.projectDir.parentFile.resolve(
                        "apps/phone/platforms/android/app/src/main/AndroidManifest.xml",
                    )
                it.systemProperty("rovsti.gpuiManifest", gpuiManifest.absolutePath)
                it.inputs.file(gpuiManifest).withPropertyName("gpuiManifest")
                // The GPUI/Rust migration fixtures are copied into src/test/resources/migration; the live source is
                // the Rust crate's, which the test compares with (a stale copy fails).
                val rustFixtures =
                    rootProject.projectDir.parentFile.resolve("crates/rovsti_app_core/tests/fixtures/dirs")
                it.systemProperty("rovsti.rustFixtureDirs", rustFixtures.absolutePath)
                it.inputs.dir(rustFixtures).withPropertyName("rustFixtureDirs")
                val ffiFixtures = rootProject.projectDir.parentFile.resolve("crates/rovsti_ffi/fixtures")
                it.systemProperty("rovsti.ffi.fixtures", ffiFixtures.absolutePath)
                it.inputs.dir(ffiFixtures).withPropertyName("ffiFixtures")
                it.inputs.files(fileTree("src/main")).withPropertyName("mainSources")
                // PhoneMigrationTest runs the real Rust core on the host library that :core-bridge builds (E2.6).
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
    implementation(project(":people"))
    implementation(project(":platform"))
    implementation(project(":wa"))
    implementation(project(":core-bridge"))
    implementation(libs.activity.compose)
    implementation(libs.coroutines.core)
    implementation(libs.collections.immutable)
    implementation(libs.navigation3.runtime)
    add("firebaseImplementation", libs.firebase.messaging)

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
