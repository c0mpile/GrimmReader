plugins {
    alias(libs.plugins.grimm.android.library)
    alias(libs.plugins.grimm.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.c0mpile.grimmreader.core.data"
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(projects.core.database)
    api(projects.core.datastore)
    api(projects.core.network)
    api(projects.core.files)
    api(projects.api.grimmory)
    api(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.coil.base)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)
}
