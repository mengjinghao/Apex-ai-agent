plugins {
    id("apex.suite.apk")
    id("apex.android.application.compose")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.apex.apk.rage"

    defaultConfig {
        applicationId = "com.apex.apk.rage"
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
    implementation(project(":core:burst-kernel"))
    implementation(project(":core:burst-mode"))
    implementation(project(":plugins:burst-base"))
    implementation(project(":plugins:burst-builtin"))
    implementation(project(":domain"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // burst-kernel uses Room for state persistence — need to expose to consumers
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
}
