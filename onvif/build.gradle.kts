plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":rtsp"))
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}
