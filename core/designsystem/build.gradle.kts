plugins {
    alias(libs.plugins.grimm.android.library)
    alias(libs.plugins.grimm.android.compose)
}

android {
    namespace = "com.c0mpile.grimmreader.core.designsystem"
}

dependencies {
    api(projects.core.model)
    api(libs.compose.material3)
    api(libs.coil.compose)
}
