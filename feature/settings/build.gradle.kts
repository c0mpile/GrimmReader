plugins {
    alias(libs.plugins.grimm.android.feature)
}

android {
    namespace = "com.c0mpile.grimmreader.feature.settings"
}

dependencies {
    implementation(projects.core.dictionary)
    implementation(libs.androidx.activity.compose)
}
