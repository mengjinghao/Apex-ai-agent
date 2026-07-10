plugins {
    id("apex.suite.apk")
    id("apex.android.application.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.apk.terminal"

    defaultConfig {
        applicationId = "com.apex.apk.terminal"
        versionCode = 1
        versionName = "1.0.0"
    }

    sourceSets {
        getByName("main") {
            aidl.srcDirs("../sdk/common-core/src/main/aidl")
        }
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
    implementation(project(":sdk:common-ui"))
    implementation(project(":ai-terminal"))
    implementation(project(":lib:terminal"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
