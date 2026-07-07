plugins {
    id("apex.suite.apk")
    id("apex.android.application.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.apk.market"

    defaultConfig {
        applicationId = "com.apex.apk.market"
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
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
}

dependencies {
    api(project(":sdk:process-bridge"))  // Explicit for AIDL source propagation
    implementation(project(":lib:market"))
    implementation(project(":sdk:common-ui"))
    implementation(project(":core:integration"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.squareup.okhttp)
}
