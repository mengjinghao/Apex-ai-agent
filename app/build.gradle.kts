import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.google.hilt.android)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "com.apex"
    compileSdk = 35

    signingConfigs {
        val releaseKeystorePath = localProperties.getProperty("RELEASE_STORE_FILE")
        val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
        val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
        val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
        if (releaseKeystorePath != null && releaseStorePassword != null &&
            releaseKeyAlias != null && releaseKeyPassword != null &&
            File(releaseKeystorePath).exists()) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.apex"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "3.0.0"
    }

    buildTypes {
        val releaseSigningConfig = signingConfigs.findByName("release")
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseSigningConfig != null) signingConfig = releaseSigningConfig
        }
        debug {
            if (releaseSigningConfig != null) signingConfig = releaseSigningConfig
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Core library desugaring (required because :engine enables it)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")

    // Network
    implementation(libs.squareup.okhttp)
    implementation(libs.squareup.okhttp.sse)

    // Serialization
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.google.gson)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Debug
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ============================================================
    // Hilt DI
    // ============================================================
    implementation(libs.google.hilt.android)
    kapt(libs.google.hilt.compiler)
    // Hilt Compose ViewModel 注入（@HiltViewModel + hiltViewModel()）
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // ============================================================
    // Room — :database 用 implementation（非 api）暴露 Room，:app 直接
    // 引用 DAO/AppDatabase 类型时需要 Room 在编译类路径上
    // ============================================================
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // ============================================================
    // 多模块项目依赖 — 单 APK 多模块架构
    // 所有 :sdk:* / :lib:* / :engine / :database 都编译进同一个 APK
    // 跨进程调用通过 ApexBridge（InProcessRegistry 优先，AIDL 兜底），
    // 未来拆 APK 时无需改业务代码
    // ============================================================

    // SDK 基础设施
    implementation(project(":sdk:common-core"))
    implementation(project(":sdk:process-bridge"))
    implementation(project(":sdk:watchdog"))
    implementation(project(":sdk:auth"))
    implementation(project(":sdk:storage"))

    // 功能库（每个 lib:* 既可被 :app 直接 implementation，也保留 :apk:* 归属
    // 规则，见 ModuleOwnershipPlugin）
    implementation(project(":lib:engine"))
    implementation(project(":lib:multi-agent"))
    implementation(project(":lib:rage"))

    // ============================================================
    // ARCH-3: Rage 三层架构（Kotlin 薄壳 → JNI 桥 → C++ 核心）
    // :lib:rage 已 api(project(":rage-jni"))，:app 通过传递依赖获得
    // RageNativeBridge 类型。这里显式 implementation 仅为清晰，避免
    // 未来 :lib:rage 切回 implementation() 时 :app 编译断裂。
    // :rage-native 提供 librage_native.so（C++17 核心），由 :rage-jni
    // 的 JNI 入口 System.loadLibrary("rage_native") 加载。
    // ============================================================
    implementation(project(":rage-native"))
    implementation(project(":rage-jni"))
    implementation(project(":lib:workflow"))
    implementation(project(":lib:market"))
    implementation(project(":lib:terminal"))
    implementation(project(":lib:voice"))
    implementation(project(":lib:working-files"))

    // 引擎服务（AIDL + Shizuku + 无障碍） + 数据层
    implementation(project(":engine"))
    implementation(project(":database"))

    // :ai-terminal — 提供 CommandRiskAssessor / DangerousCommandPatterns / RiskLevel
    // 用于 SafeShellTool 在执行前做命令风险评估
    implementation(project(":ai-terminal"))
}
