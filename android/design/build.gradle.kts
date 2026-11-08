plugins {
    id("rovsti.android.library")
    id("rovsti.compose")
}

android {
    namespace = "dev.rovsti.design"
}

dependencies {
    // Public signatures take ImmutableList (stable for Compose), so the type is part of the API.
    api(libs.collections.immutable)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.core)
    // TopFadeBlur (ported from Convx) blurs through Haze; Apache-2.0, listed in THIRD_PARTY_NOTICES.md.
    implementation(libs.haze)
    implementation(libs.haze.materials)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // StaticRegionTest and NavTransitionFreezeTest compose a real LayerBackdrop under Robolectric.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
}
