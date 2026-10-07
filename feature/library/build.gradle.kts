plugins {
    alias(libs.plugins.grimm.android.feature)
}

android {
    namespace = "com.c0mpile.grimmreader.feature.library"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
