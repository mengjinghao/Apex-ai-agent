plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.agent.burstmode"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // 依赖 burst-kernel 微内核（核心执行引擎）
    implementation(project(":core:burst-kernel"))
    // 依赖 burst-base 插件抽象层（IBurstSkill 等接口）
    implementation(project(":plugins:burst-base"))
    // 依赖 domain（BurstTask 等领域模型）
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.oshai.kotlin.logging.jvm)

    testImplementation(libs.junit.junit)
}
