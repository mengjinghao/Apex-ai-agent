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
    implementation(project(":sdk:common-ui"))
    implementation(project(":core:burst-kernel"))
    implementation(project(":core:burst-mode"))
    implementation(project(":plugins:burst-base"))
    implementation(project(":plugins:burst-builtin"))
    implementation(project(":domain"))

    // 狂暴模式核心库（4 Agent 架构师 + 31 技能目录 + 引擎 + 内存任务存储）
    implementation(project(":lib:rage"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    // burst-kernel uses Room for state persistence — need to expose to consumers
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
}
