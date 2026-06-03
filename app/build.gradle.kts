import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.fytbt"
    // compileSdk 35 is required by current AndroidX core-ktx; runtime behavior still gated by targetSdk 33.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fytbt"
        // Android 10 (API 29) and up — the new runtime BT permissions are 31+, but we branch to the
        // legacy BLUETOOTH/BLUETOOTH_ADMIN + location model on 29–30 (see MainActivity/BluetoothController).
        minSdk = 29
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    // Real release signing, populated from env vars set by CI (.github/workflows/release.yml):
    //   FYTBT_KEYSTORE_PATH       — absolute path to a decoded .jks
    //   FYTBT_KEYSTORE_PASSWORD   — store password (also used as the key password)
    //   FYTBT_KEY_ALIAS           — alias inside the keystore (defaults to "fytbt")
    // When unset (local dev, forks), the release build falls back to the per-machine debug keystore
    // below — local builds still work, just signed with a different cert. In-place upgrades only
    // matter between releases that BOTH come from CI, so the fallback is fine for development.
    signingConfigs {
        create("releaseFromEnv") {
            val path = System.getenv("FYTBT_KEYSTORE_PATH")
            if (!path.isNullOrBlank() && File(path).exists()) {
                storeFile = File(path)
                storePassword = System.getenv("FYTBT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FYTBT_KEY_ALIAS") ?: "fytbt"
                keyPassword = System.getenv("FYTBT_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Kept unminified for now — the reflective BT calls + SYU binder IPC aren't R8-audited yet.
            isMinifyEnabled = false
            val envSigning = signingConfigs.getByName("releaseFromEnv")
            signingConfig = if (envSigning.storeFile?.exists() == true) {
                envSigning
            } else {
                signingConfigs.getByName("debug")
            }
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
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.palette)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
