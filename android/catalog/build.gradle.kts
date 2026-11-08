plugins {
    id("rovsti.android.app")
    id("rovsti.compose")
    id("rovsti.screenshots")
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
    implementation(libs.activity.compose)
    testImplementation(project(":testing"))
}
