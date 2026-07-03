plugins {
    id("apex.suite.apk")
    id("apex.android.application.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.apk.workflow"

    defaultConfig {
        applicationId = "com.apex.apk.workflow"
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
    implementation(project(":sdk:common-ui"))
    implementation(project(":lib:workflow"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
