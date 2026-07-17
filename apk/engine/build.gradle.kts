plugins {
    id("apex.suite.apk")
    id("apex.android.application.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.apk.engine"

    defaultConfig {
        applicationId = "com.apex.apk.engine"
        versionCode = 6
        versionName = "1.0.5"
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
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
    // SDK deps auto-injected by apex.suite.apk convention plugin
    implementation(project(":sdk:common-ui"))
    implementation(project(":engine"))
    // 私有领域层：Shell/工具/容器/Shizuku 编排逻辑
    implementation(project(":lib:engine"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
