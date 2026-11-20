plugins {
    id("rosty.android.library")
}

// The map painting Stops and Messages share (E6-WA R5): a decoded tile made ready to paint, the raster tiles of
// Stops' map and the small static maps of Messages' place cards. No Compose, no screens; the tiles come from the
// native source (`:core-bridge`'s TileHandle).
android {
    namespace = "dev.rosty.map"
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":core-bridge"))
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":core-bridge")))
}
