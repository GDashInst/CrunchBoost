plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.crunchboost"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.crunchboost"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        externalNativeBuild {
            cmake {
                // Oboe's prefab package requires the shared C++ runtime.
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    // A fixed debug key (committed on purpose) so each new build installs
    // over the previous one instead of forcing an uninstall.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.oboe:oboe:1.9.0")
}
