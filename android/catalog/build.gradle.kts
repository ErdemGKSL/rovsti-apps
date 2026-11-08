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

    buildTypes {
        release {
            // Debug-only demo app: never shipped.
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":design"))
    implementation(project(":core-bridge"))
    implementation(libs.activity.compose)
    implementation(libs.haze)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    testImplementation(project(":testing"))
}
