plugins {
    alias(libs.plugins.grimm.android.library)
    alias(libs.plugins.grimm.android.compose)
}

android {
    namespace = "com.c0mpile.grimmreader.reader.pdf"
}

dependencies {
    api(projects.reader.paged)
    implementation(libs.kotlinx.coroutines.android)
}
