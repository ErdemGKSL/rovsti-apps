plugins {
    id("rovsti.android.library")
    id("rovsti.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rovsti.design"
}

dependencies {
    // Public signatures take ImmutableList (stable for Compose), so the type is part of the API.
    api(libs.collections.immutable)
    // Typed routes: TabNavigator saves its stacks as JSON through the routes' KSerializer (part of the API).
    api(libs.kotlinx.serialization.json)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.core)
    // TopFadeBlur (ported from Convx) blurs through Haze; Apache-2.0, listed in THIRD_PARTY_NOTICES.md.
    implementation(libs.haze)
    implementation(libs.haze.materials)
    // Image loading (Apache-2.0, THIRD_PARTY_NOTICES.md). No network fetcher: apps load content:// and file URIs.
    api(libs.coil.core)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // StaticRegionTest and NavTransitionFreezeTest compose a real LayerBackdrop under Robolectric.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
}
