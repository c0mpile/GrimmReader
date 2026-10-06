plugins {
    alias(libs.plugins.grimm.jvm.library)
    alias(libs.plugins.grimm.hilt)
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    api(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // The source scan in HttpClientInvariantTest walks the whole repository.
    systemProperty("grimm.root", rootDir.absolutePath)
}
