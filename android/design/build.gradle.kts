plugins {
    id("rovsti.android.library")
    id("rovsti.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rovsti.design"
}

android {
    testOptions {
        unitTests.all {
            // Source-scanning tests (ConventionTest, NoFlatInTestsTest) read every module's sources: re-run when they change.
            it.inputs
                .files(
                    rootProject.fileTree(rootDir) { include("*/src/**/*.kt") },
                ).withPropertyName("allKotlinSources")
        }
    }
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
    // The reorderable list (Convx port, list/Reorder.kt); apps use its ReorderableItem and draggableHandle.
    api(libs.compose.reorderable)
    api(libs.coil.core)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    // TabBarCellsTest uses the ShotTest harness (the :testing module depends on :design main, not on its tests).
    testImplementation(project(":testing"))
    testImplementation(libs.coroutines.test)
    // StaticRegionTest and NavTransitionFreezeTest compose a real LayerBackdrop under Robolectric.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
}

// Compose compiler report gate (E1.21 AC3): `bash scripts/kotlin-ui-check.sh` runs
// `./gradlew -PcomposeReports=true :design:checkComposeStability`. These are the hot composables of the Convx port
// (they recompose on every frame of a drag or scroll); every overload must be restartable and skippable.
tasks.named<CheckComposeStabilityTask>("checkComposeStability") {
    hot.set(
        listOf(
            "FloatingTabBar",
            "FoldingTabBar",
            "FoldingTabGroup",
            "FoldingTabCell",
            "Tab",
            "RovstiTabBar",
            "FrostedTopBar",
            "IosNavigationBar",
            "IosTabBar",
            "TabBarItem",
            "IosList",
            "IosListRowContainer",
            "IosSwipeableRow",
            "IosMenu",
            "IosMenuRow",
            "IosMenuQuickAction",
            "IosMenuSeparator",
            "IosToggle",
            "IosSlider",
            "SegmentedControl",
            "GlassTextField",
            "AlphabetScrollBar",
        ),
    )
}
