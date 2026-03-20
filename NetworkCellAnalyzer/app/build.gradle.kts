plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.eece451.networkcellanalyzer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eece451.networkcellanalyzer"
        minSdk = 26          // Android 8.0 - supports all the telephony APIs we need
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Android core libraries
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // OkHttp for sending data to the server
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines for background work
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
