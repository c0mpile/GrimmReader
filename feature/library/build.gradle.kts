plugins {
    alias(libs.plugins.grimm.android.feature)
}

android {
    namespace = "com.c0mpile.grimmreader.feature.library"
}

dependencies {
    implementation(projects.feature.bookdetail)
    implementation(libs.androidx.activity.compose)
}
