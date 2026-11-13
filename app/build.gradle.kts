plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uz.speakpartner"
    compileSdk = 34

    defaultConfig {
        applicationId = "uz.speakpartner"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("emma") {
            storeFile = file("emma.keystore")
            storePassword = "emma1234"
            keyAlias = "emma"
            keyPassword = "emma1234"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("emma")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("emma")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
