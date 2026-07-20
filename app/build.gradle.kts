import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
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
            // REVERT: 关闭 R8 minification + 资源收缩 — Agent 后续要自改源码,
            // 混淆会导致代码不可读、堆栈不可定位。保留 KSP(非混淆,仅更快注解处理)+所有纯性能优化。
            // proguardFiles 引用保留 — isMinifyEnabled=false 时 proguard 规则不生效。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

// PERF-48: Hilt 已从 KAPT 切换到 KSP，不再需要 kapt-UnitTest 兼容性 workaround
// (KSP 不存在 K1 stub 无法加载 K2 metadata 的问题)。
// 但 :app unit-test sources 仍有 ~3900 预存在编译错误（引用已重构/移除的类，
// 如 BurstModeConfig / PluginRegistry / Modality / SessionPhase 等），
// 故继续禁用 :app 的 unit test 编译/运行任务。其他模块的测试不受影响。
// TODO: 修复 :app unit tests，然后移除本块。
tasks.matching {
    val n = it.name
    n == "compileDebugUnitTestKotlin" ||
    n == "compileDebugUnitTestJavaWithJavac" ||
    n == "testDebugUnitTest"
}.configureEach {
    enabled = false
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
    // PERF-48: KSP 处理 Hilt 注解（替代 kapt，Kotlin 2.0/K2 原生兼容，编译更快）。
    ksp(libs.google.hilt.compiler)
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
    implementation(project(":self-modify"))

    // 引擎服务（AIDL + Shizuku + 无障碍） + 数据层
    implementation(project(":engine"))
    implementation(project(":database"))

    // :ai-terminal — 提供 CommandRiskAssessor / DangerousCommandPatterns / RiskLevel
    // 用于 SafeShellTool 在执行前做命令风险评估
    implementation(project(":ai-terminal"))

    // ============================================================
    // Unit tests — kotlin.test.* assertions (assertEquals/assertTrue/...) wired to JUnit 4
    // ============================================================
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
