// build-logic/convention/build.gradle.kts
// Convention plugin 模块的构建配置

plugins {
    `kotlin-dsl`
}

group = "com.apex.agent.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        // Android Application 模块约定
        register("androidApplication") {
            id = "apex.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        // Android Library 模块约定
        register("androidLibrary") {
            id = "apex.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        // Android Library + Compose 约定
        register("androidLibraryCompose") {
            id = "apex.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        // Android Application + Compose 约定
        register("androidApplicationCompose") {
            id = "apex.android.application.compose"
            implementationClass = "AndroidApplicationComposeConventionPlugin"
        }
        // 纯 Kotlin JVM 模块约定
        register("kotlinJvm") {
            id = "apex.kotlin.jvm"
            implementationClass = "KotlinJvmConventionPlugin"
        }
        // Apex 套件 APK 模块约定（自动注入 SDK + 共享进程占位符）
        register("apexSuiteApk") {
            id = "apex.suite.apk"
            implementationClass = "ApexSuiteApkConventionPlugin"
        }
        // 模块归属校验（防止 lib:* 被错误地打包进非授权 APK）
        register("moduleOwnership") {
            id = "apex.module.ownership"
            implementationClass = "ModuleOwnershipPlugin"
        }
    }
}
