plugins {
    alias(libs.plugins.grimm.android.feature)
}

android {
    namespace = "com.c0mpile.grimmreader.feature.settings"
}

dependencies {
    implementation(projects.core.dictionary)
    implementation(projects.core.tts)
    implementation(libs.androidx.activity.compose)
}
