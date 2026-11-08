plugins {
    id("rovsti.android.library")
    id("rovsti.compose")
}

android {
    namespace = "dev.rovsti.design"
}

dependencies {
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
