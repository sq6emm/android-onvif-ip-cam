plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "pl.arhea.onvifipcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.arhea.onvifipcam"
        minSdk = 26
        targetSdk = 35
        // Minutes since 2024-01-01: every newer build (local or CI) installs over an older one.
        versionCode = (findProperty("versionCode") as String?)?.toInt()
            ?: ((System.currentTimeMillis() / 60_000) - 28_401_120).toInt()
        // CI sets this from the tag for releases.
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"
    }

    signingConfigs {
        // Fixed key committed to the repo so every build (any machine/container) can update the installed app.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":rtsp"))
    implementation(project(":onvif"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
}
