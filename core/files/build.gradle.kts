plugins {
    alias(libs.plugins.grimm.android.library)
    alias(libs.plugins.grimm.hilt)
}

android {
    namespace = "com.c0mpile.grimmreader.core.files"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    api(libs.commons.compress)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
