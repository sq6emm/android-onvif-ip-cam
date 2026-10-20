plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("pl.arhea.onvifipcam.devserver.MainKt")
}

dependencies {
    implementation(project(":rtsp"))
    implementation(project(":onvif"))
}
