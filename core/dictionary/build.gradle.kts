plugins {
    alias(libs.plugins.grimm.android.library)
    alias(libs.plugins.grimm.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.c0mpile.grimmreader.core.dictionary"
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.files)
    implementation(projects.core.network)
    implementation(libs.kotlinx.serialization.json)
    // Decompressors for downloaded archives (commons-compress uses them): FreeDict .tar.xz, Wiktionary .tar.zst.
    implementation(libs.xz)
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    // The plain jar carries desktop natives, so unit tests can unpack .tar.zst too.
    testImplementation(libs.zstd.jni)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
