plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.c0mpile.grimmreader.spike.panels.bench.BenchKt"
}

dependencies {
    implementation(project(":detector"))
}
