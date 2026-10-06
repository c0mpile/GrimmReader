plugins {
    alias(libs.plugins.grimm.jvm.library)
    alias(libs.plugins.grimm.hilt)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
