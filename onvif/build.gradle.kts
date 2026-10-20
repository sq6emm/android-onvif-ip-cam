plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":rtsp"))
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}
