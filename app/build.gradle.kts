plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "iq.uor.ran"
    compileSdk = 36

    defaultConfig {
        applicationId = "iq.uor.ran"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

    }

    // Fixed key so every GitHub build installs as an UPDATE over the previous one
    signingConfigs {
        getByName("debug") {
            storeFile = file("ran.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "ran-dev-key"
            keyAlias = System.getenv("KEY_ALIAS") ?: "ran"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "ran-dev-key"
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // QR codes (generate + scan)
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // WebRTC: voice + video calls, screen sharing (local Wi-Fi only)
    implementation("io.getstream:stream-webrtc-android:1.3.9")
}
