plugins {
    alias(libs.plugins.grimm.android.library)
    alias(libs.plugins.grimm.hilt)
}

android {
    namespace = "com.c0mpile.grimmreader.core.files"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
