import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.compose)
    id("io.objectbox")
    alias(libs.plugins.google.hilt.android)
    // 模块归属校验 — 防止 lib:* 被打包进主 APK
    id("apex.module.ownership")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "com.apex.agent"

    compileSdk = 35
    buildToolsVersion = "34.0.0"

    signingConfigs {
        val releaseKeystorePath = localProperties.getProperty("RELEASE_STORE_FILE")
        val releaseStorePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
        val releaseKeyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
        val releaseKeyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")

        if (releaseKeystorePath != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null &&
            File(releaseKeystorePath).exists()
        ) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    defaultConfig {
        applicationId = "com.apex.agent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        
        ndk {
            // Explicitly specify the ABIs to support. This ensures that native libraries
            // for both 32-bit and 64-bit ARM devices are included in the APK,
            // resolving conflicts between dependencies with different native library sets.
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
            }
        }

        buildConfigField("String", "GITHUB_CLIENT_ID", "\"${localProperties.getProperty("GITHUB_CLIENT_ID")}\"")
        buildConfigField("String", "GITHUB_CLIENT_SECRET", "\"${localProperties.getProperty("GITHUB_CLIENT_SECRET")}\"")

        val apiBaseUrl = localProperties.getProperty("API_BASE_URL") ?: "http://10.0.2.2:8080"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
        val releaseSigningConfig = signingConfigs.findByName("release")

        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
        debug {
            if (releaseSigningConfig != null) {
                signingConfig = releaseSigningConfig
            }
        }
        create("nightly") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (releaseSigningConfig != null) {
                releaseSigningConfig
            } else {
                signingConfigs.getByName("debug")
            }
            matchingFallbacks += listOf("release")
        }
    }
    applicationVariants.all {
        if (buildType.name == "nightly") {
            outputs.all {
                val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                output.outputFileName = "app-nightly.apk"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
        viewBinding = true
    }

    
    packaging {
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE-EPL-1.0.txt"
            excludes += "LICENSE-EPL-1.0.txt"
            excludes += "/META-INF/LICENSE-EDL-1.0.txt"
            excludes += "LICENSE-EDL-1.0.txt"
            
            // Resolve merge conflicts for document libraries
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/ASL2.0"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "META-INF/versions/9/module-info.class"
            
            // Fix for duplicate Netty files
            excludes += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/INDEX.LIST"
            
            // Fix for any other potential duplicate files
            pickFirsts += "**/*.so"
        }
    }
//    aaptOptions {
//        noCompress += "tflite"
//    }
}

// Room schema 导出目录配置（用于迁移验证）
kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

// Kotlin 编译配置
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.github.jelmerk:hnswlib-core:1.2.1")
    // implementation(project(":dragonbones")) // 暂时禁用 - C++ 构建有问题
    implementation(project(":ai-terminal"))
    // implementation(project(":mnn"))
    // implementation(project(":mmd"))
    // implementation(project(":fbx"))
    // implementation(project(":showerclient"))
    // implementation(project(":quickjs"))
    // implementation(project(":vision"))
    // implementation(project(":nlp"))
    implementation(project(":file"))

    // ============================================================
    // Apex 套件 SDK — 主 APK 作为“零延迟”通信的枢纽
    // ============================================================
    implementation(project(":sdk:common-core"))
    implementation(project(":sdk:common-ui"))
    implementation(project(":sdk:process-bridge"))
    implementation(project(":sdk:watchdog"))
    implementation(project(":sdk:auth"))
    implementation(project(":sdk:storage"))

    // ============================================================
    // ⚠️ 模块归属规则（见 docs/architecture/MODULE_OWNERSHIP.md）
    // ============================================================
    // lib:* 模块是"功能 APK 私有库"，只打包进对应 APK，不打包进主 APK。
    // 主 APK 通过 ApexClient 跨 APK 调用这些功能，无需直接依赖。
    //
    //   :lib:multi-agent   → 只属于 :apk:multi-agent
    //   :lib:workflow      → 只属于 :apk:workflow
    //   :lib:working-files → 只属于 :apk:working-files
    //
    // 如需在主 APK 中使用，应通过：
    //   ApexClient.workingFiles.startAgentSession(...)
    //   ApexClient.workflow.execute(...)
    //   ApexClient.multiAgent.runCollaboration(...)
    // ============================================================

    // Burst Mode 微内核架构模块
    implementation(project(":domain"))
    implementation(project(":core:burst-kernel"))
    implementation(project(":plugins:burst-base"))
    implementation(project(":plugins:burst-builtin"))

    // glTF runtime rendering (Filament)
    implementation("com.google.android.filament:filament-android:1.69.2")
    implementation("com.google.android.filament:gltfio-android:1.69.2")
    implementation("com.google.android.filament:filament-utils-android:1.69.2")
    implementation(libs.androidx.compose.ui.graphics)
    // Vendored binary dependencies live in app/libs, including ffmpeg-kit and its Java-side deps.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // Desugaring support for modern Java APIs on older Android
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)

    // ML Kit - 文本识别
    implementation(libs.google.mlkit.text.recognition)
    implementation(libs.google.mlkit.text.recognition.chinese)
    implementation(libs.google.mlkit.text.recognition.japanese)
    implementation(libs.google.mlkit.text.recognition.korean)
    implementation(libs.google.mlkit.text.recognition.devanagari)

    // Supabase 云端同步 - 暂注释，依赖在 Maven Central 不可用
    // implementation(libs.supabase.kt.postgres)
    // implementation(libs.supabase.kt.gotrue)
    // implementation(libs.supabase.kt.realtime)
    // implementation(libs.supabase.kt.storage)
    
    implementation(libs.google.zxing.core)
    
    // diff
    implementation(libs.java.diff.utils)
    
    // APK解析和修改库
    implementation(libs.android.tools.apksig)
    implementation(libs.net.dongliu.apk.parser)
    implementation(libs.github.sable.axml)
    implementation(libs.github.iyxan23.zipalign.java)
    
    // ZIP处理库 - 用于APK解压和重打包
    implementation(libs.apache.commons.compress)
    implementation(libs.apache.commons.io)
    
    // 图片处理库 - Glide 已移除，使用 Coil 替代
    
    // libsu - root access library
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("com.github.topjohnwu.libsu:nio:6.0.0")
    
    // Add missing SVG support
    implementation(libs.caverock.androidsvg)
    
    // Add missing GIF support for Markwon
    implementation(libs.pl.droidsonroids.android.gif.drawable)
    
    // Image Cropper for background image cropping
    implementation(libs.vanniktech.android.image.cropper)
    
    // ExoPlayer for video background
    implementation(libs.google.exoplayer)
    implementation(libs.google.exoplayer.core)
    implementation(libs.google.exoplayer.ui)
    
    // Material 3 Window Size Class
    implementation("androidx.compose.material3:material3-window-size-class:1.1.1")
    
    // Window metrics library for foldables and adaptive layouts
    implementation(libs.androidx.window)
    implementation(libs.androidx.webkit)

    // Document conversion libraries
    implementation(libs.itextpdf.itextg)
    implementation(libs.tomroush.pdfbox.android)
    implementation(libs.lingala.zip4j)
    
    // 图片加载库
    implementation(libs.coil.kt.coil)
    implementation(libs.coil.kt.coil.compose)
    implementation(libs.coil.kt.coil.gif)
    
    // LaTeX rendering libraries - 暂时禁用，库下载失败
    // implementation("io.github.sidvenu:androidmathview:2.0.3")
    
    // Base Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // UUID dependencies
    implementation(libs.benasher44.uuid)
    
    // Gson for JSON parsing
    implementation(libs.google.gson)

    // HJSON dependency for human-friendly JSON parsing
    implementation(libs.hjson)

    // 中文分词库 - Jieba Android
    implementation(libs.jieba.analysis)

    // 向量搜索库 - 轻量级实现，适合Android
    implementation(libs.github.jelmerk.hnswlib.core)
    implementation(libs.github.jelmerk.hnswlib.utils)

    // ONNX Runtime for Android - 用于 Silero VAD 语音活动检测
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")

    // Hilt 依赖注入
    implementation(libs.google.hilt.android)
    kapt(libs.google.hilt.compiler)
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Room 数据库
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // ObjectBox
    implementation(libs.objectbox.kotlin)
    kapt(libs.objectbox.processor)
    implementation(libs.junrar.junrar)

    // Compose dependencies - use BOM for version consistency
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.animation.core)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // Shizuku dependencies
    implementation(libs.rikka.shizuku.api)
    implementation(libs.rikka.shizuku.provider)

    // Tasker Plugin Library
    implementation("com.joaomgcd:taskerpluginlibrary:0.4.10")
    
    // WorkManager for scheduled workflows
    implementation(libs.androidx.work.runtime.ktx)

    // Network dependencies
    implementation(libs.squareup.okhttp)
    implementation(libs.squareup.okhttp.sse)
    implementation(libs.jsoup.jsoup)

    // DataStore dependencies
    implementation(libs.androidx.datastore.preferences)

    // Debug dependencies
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Test dependencies
    testImplementation(libs.junit.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    
    // Apache POI - for Document processing (DOC, DOCX, etc.)
    implementation(libs.apache.poi)
    implementation(libs.apache.poi.ooxml)
    implementation(libs.apache.poi.scratchpad)

    // Kotlin logging
    implementation(libs.oshai.kotlin.logging.jvm)
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.simple)

    // Color picker - 替换为标准库
    implementation("com.github.skydoves:colorpickerview:2.2.0")
    
    // NanoHTTPD for local web server
    implementation(libs.nanohttpd)

    testImplementation(libs.archunit)

    // Android测试依赖
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    
    // 协程测试依赖
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    
    // 模拟测试框架
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    androidTestImplementation(libs.mockito.android)
    
    implementation(libs.reorderable)

    // Swipe to reveal actions
    implementation(libs.swipe)

    // Coroutine
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // ViewPager2 & RecyclerView & Preference
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.preference.ktx)

    implementation(libs.modelcontextprotocol.mcp)
    
    // Exclude bcprov-jdk15to18 from all configurations to avoid duplicate classes
    configurations.all {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // BouncyCastle - explicitly include jdk18on version to avoid conflicts
    implementation("org.bouncycastle:bcprov-jdk18on:1.78")

    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    // implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.9.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")


    // Accompanist
    implementation(libs.google.accompanist.systemuicontroller)

    // Glance for Widgets (Compose for Widgets)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
}

// Fix AAPT2 Windows path issue: disable incremental compilation
tasks.withType<com.android.build.gradle.tasks.MergeResources>().configureEach {
    outputs.upToDateWhen { false }
    doFirst {
        val incrementalDir = project.layout.buildDirectory.dir("intermediates/incremental/${name}").get().asFile
        if (incrementalDir.exists()) {
            incrementalDir.deleteRecursively()
        }
    }
}




