plugins {
    alias(libs.plugins.grimm.android.library)
    alias(libs.plugins.grimm.hilt)
    alias(libs.plugins.grimm.room)
}

android {
    namespace = "com.c0mpile.grimmreader.core.database"
}

dependencies {
    api(projects.core.model)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
