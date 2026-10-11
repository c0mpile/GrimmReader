plugins {
    alias(libs.plugins.grimm.android.feature)
}

android {
    namespace = "com.c0mpile.grimmreader.feature.reader"
}

dependencies {
    implementation(projects.core.dictionary)
    implementation(projects.core.tts)
    implementation(projects.reader.ebook)
    implementation(projects.reader.comic)
    implementation(projects.reader.pdf)
    implementation(libs.androidx.navigationevent.compose)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.mockk)
}
