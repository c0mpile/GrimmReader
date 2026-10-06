plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.c0mpile.grimmreader.spike.eink"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.c0mpile.grimmreader.spike.eink"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
}
