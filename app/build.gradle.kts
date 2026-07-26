plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wesrable.positioning"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wesrable.positioning"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Pinned so every CI build shares one signature. Without this, the
        // default debug config auto-generates a fresh, random signing key
        // whenever ~/.android/debug.keystore doesn't already exist — true on
        // every CI run, since each one starts on a clean machine. Android
        // refuses to install an APK as an *update* over one signed by a
        // different key, so every build required an uninstall first. This
        // keystore's password/alias are the well-known Android debug
        // defaults ("android" / "androiddebugkey") — not a secret, since
        // debug builds were never meant to be tamper-proof, only convenient.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // CameraX supplies frames for on-device marker reading. camera-view is
    // included for its PreviewView, since aiming at a cabinet without seeing
    // what the camera sees is guesswork.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
