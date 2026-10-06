plugins {
    id("com.android.application")
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.c0mpile.grimmreader.spike.panels.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.c0mpile.grimmreader.spike.panels"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0"
    }
}

dependencies {
    implementation(project(":detector"))
}
