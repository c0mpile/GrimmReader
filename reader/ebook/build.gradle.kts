plugins {
    alias(libs.plugins.grimm.android.library)
    alias(libs.plugins.grimm.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.c0mpile.grimmreader.reader.ebook"
}

dependencies {
    implementation(projects.core.files)
    api(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
}
