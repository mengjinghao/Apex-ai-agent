plugins {
    id("apex.suite.apk")
    id("apex.android.application.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.apk.workingfiles"

    defaultConfig {
        applicationId = "com.apex.apk.workingfiles"
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
    implementation(project(":lib:working-files"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(libs.androidx.compose.material.icons.extended)
}
