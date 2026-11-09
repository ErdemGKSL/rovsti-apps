plugins {
    id("rovsti.android.app")
    id("rovsti.compose")
    id("rovsti.screenshots")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rovsti.catalog"

    defaultConfig {
        applicationId = "dev.rovsti.catalog"
        versionCode = 1
        versionName = "0.1"
    }

    lint {
        // :platform's TripService (linked only for the WhatsApp settings page's clipboard and files seams) posts a
        // notification; the demo app never starts it, and real apps declare the permission (E3.8b).
        disable += "NotificationPermission"
    }

    buildTypes {
        release {
            // Debug-only demo app: never shipped.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

dependencies {
    implementation(project(":design"))
    implementation(project(":core-bridge"))
    implementation(project(":wa"))
    implementation(project(":platform"))
    implementation(project(":shell"))
    // The WhatsApp settings page runs the real view model on scripted fakes (no gateway, no Rust).
    implementation(testFixtures(project(":wa")))
    implementation(testFixtures(project(":core-bridge")))
    implementation(testFixtures(project(":platform")))
    implementation(libs.activity.compose)
    implementation(libs.haze)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    testImplementation(project(":testing"))
}
