plugins {
    alias(libs.plugins.grimm.android.library)
    alias(libs.plugins.grimm.hilt)
    alias(libs.plugins.grimm.room)
}

android {
    namespace = "com.c0mpile.grimmreader.core.database"
}

// MigrationTestHelper reads the exported schemas as test assets.
androidComponents {
    onVariants { variant ->
        variant.hostTests.values.forEach { it.sources.assets?.addStaticSourceDirectory("$projectDir/schemas") }
    }
}

dependencies {
    api(projects.core.model)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
