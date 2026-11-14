plugins {
    id("rosty.android.library")
    id("rosty.compose")
}

android {
    namespace = "dev.rosty.testing"
}

// Test-only support: apps and libraries use it as `testImplementation(project(":testing"))`.
dependencies {
    api(project(":design"))
    api(libs.robolectric)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
    api(libs.roborazzi.junit.rule)
    api(libs.compose.ui.test.junit4)
    api(libs.junit)
}
