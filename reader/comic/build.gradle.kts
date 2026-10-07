plugins {
    alias(libs.plugins.grimm.android.library)
    alias(libs.plugins.grimm.android.compose)
}

android {
    namespace = "com.c0mpile.grimmreader.reader.comic"
}

dependencies {
    api(projects.reader.paged)
    implementation(projects.core.files)
    implementation(libs.kotlinx.coroutines.android)
}
