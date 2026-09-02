// AGP 9.0+ has built-in Kotlin support — no separate kotlin.android plugin needed.
// Compose Compiler + KSP + kotlinx-serialization plugins are applied here.
// Version matrix is locked at Kotlin 2.3.10 + KSP 2.3.10 + Compose Compiler 2.3.10
// because Kotlin 2.4.x has no KSP yet (KSP maxes at 2.3.11), and 2.3.21/2.3.20
// have no matching KSP. 2.3.10 is the sweet spot where all three align.
// See https://kotl.in/gradle/agp-built-in-kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.mindmax"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.mindmax"
        minSdk = 26
        targetSdk = 37
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

    // Room schema export — committed to VCS so migration diffs are reviewable.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
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

    // Room 2.8.4 (the latest stable in androidx.room; Room 3.0 dropped Kotlin 2.3.x support)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Encrypted storage for API keys
    implementation(libs.androidx.security.crypto)

    // DataStore for non-secret prefs
    implementation(libs.androidx.datastore.preferences)

    // WorkManager (used by KeepAliveWorker in the service layer)
    implementation(libs.androidx.work.runtime.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // kotlinx-serialization (for LLM request/response DTOs in C3)
    implementation(libs.kotlinx.serialization.json)

    // Retrofit + OkHttp (used by LlmClient in C3)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp.logging)
}
