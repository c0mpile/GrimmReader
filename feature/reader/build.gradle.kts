plugins {
    alias(libs.plugins.grimm.android.feature)
}

android {
    namespace = "com.c0mpile.grimmreader.feature.reader"
}

dependencies {
    implementation(projects.reader.ebook)
    implementation(projects.reader.comic)
}
