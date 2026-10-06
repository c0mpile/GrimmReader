plugins {
    alias(libs.plugins.grimm.android.application)
    alias(libs.plugins.grimm.android.compose)
    alias(libs.plugins.grimm.hilt)
}

android {
    namespace = "com.c0mpile.grimmreader"
    defaultConfig {
        applicationId = "com.c0mpile.grimmreader"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.database)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
}
