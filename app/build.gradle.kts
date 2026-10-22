plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "xyz.bodyparts.home"
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.bodyparts.home"
        minSdk = 25          // Fire OS 6+
        targetSdk = 28       // Fire OS 7 is Android 9
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Not debuggable + R8-optimised: starts and runs noticeably faster than the debug APK
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the debug key so it can be sideloaded straight away
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // targetSdk 28 is deliberate for Fire OS; don't let lint fail the build over it
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
