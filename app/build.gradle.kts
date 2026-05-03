plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.agente.autonomo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.agente.autonomo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Room schema export directory for migration tracking
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
        // Keep ONNX Runtime shared library ABI splits
        jniLibs {
            useLegacyPackaging = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            // arm64-v8a covers >90 % of Android 8+ devices
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.work.runtime.ktx)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // JSON
    implementation(libs.gson)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // ── ONNX Runtime Android (MiniLM embedding inference) ──────────────────────
    // Provides OrtEnvironment / OrtSession used by EmbeddingEngine
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.3")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
