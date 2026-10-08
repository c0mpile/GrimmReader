import java.util.Properties

plugins {
    alias(libs.plugins.grimm.android.application)
    alias(libs.plugins.grimm.android.compose)
    alias(libs.plugins.grimm.hilt)
    alias(libs.plugins.kotlin.serialization)
}

/** Debug-only server prefill: env GRIMMREADER_SERVER_URL or untracked local.properties. Never in release. */
val devServerUrl: String =
    providers.environmentVariable("GRIMMREADER_SERVER_URL").orNull
        ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
            Properties().apply { file.inputStream().use { load(it) } }.getProperty("GRIMMREADER_SERVER_URL")
        }
        ?: ""

android {
    namespace = "com.c0mpile.grimmreader"
    defaultConfig {
        applicationId = "com.c0mpile.grimmreader"
        versionCode = 2
        versionName = "0.1.1"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "DEV_SERVER_URL", "\"${devServerUrl.replace("\"", "")}\"")
        }
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
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.feature.setup)
    implementation(projects.feature.library)
    implementation(projects.feature.bookdetail)
    implementation(projects.feature.reader)
    implementation(projects.feature.settings)
    implementation(projects.feature.notebook)
    implementation(projects.feature.downloads)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.hilt.work)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.serialization.json)
}
