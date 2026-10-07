plugins {
    alias(libs.plugins.grimm.android.library)
    alias(libs.plugins.grimm.android.compose)
}

android {
    namespace = "com.c0mpile.grimmreader.reader.paged"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(libs.telephoto.zoomable)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
