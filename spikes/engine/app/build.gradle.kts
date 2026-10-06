plugins {
    id("com.android.application")
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.c0mpile.grimmreader.spike.engine"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.c0mpile.grimmreader.spike.engine"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.17.1")
}
