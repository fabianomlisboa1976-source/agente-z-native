// AGP 9.0+ has built-in Kotlin support — no separate kotlin.android plugin needed.
// Only the Compose Compiler plugin (org.jetbrains.kotlin.plugin.compose) is required
// for Compose code generation. See https://kotl.in/gradle/agp-built-in-kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.mindmax"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mindmax"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "4.0.0"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            // Personal-use build: default debug signingConfig keeps the release APK installable.
            // isMinifyEnabled stays false so we can read stack traces while iterating.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
