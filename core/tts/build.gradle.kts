plugins {
    alias(libs.plugins.grimm.android.library)
    alias(libs.plugins.grimm.hilt)
}

android {
    namespace = "com.c0mpile.grimmreader.core.tts"
}

dependencies {
    implementation(projects.core.common)
    implementation(libs.androidx.core.ktx)
    api(projects.core.datastore)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
