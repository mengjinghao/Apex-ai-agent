buildscript {
    val objectboxVersion by extra("3.7.1")
    System.setProperty("objectbox.properties", rootProject.file(".objectbox-build.properties").absolutePath)
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        classpath("io.objectbox:objectbox-gradle-plugin:$objectboxVersion")
        // 注意：AGP 和 Kotlin 插件版本由 plugins 块统一管理，避免与 classpath 版本冲突
        // classpath("com.android.tools.build:gradle:8.5.2")
        // classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.google.hilt.android) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// Common configuration for all subprojects
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions {
            jvmTarget = "17"
        }
    }
}

// Apply Groovy build script for agent compilation (applies Groovy DSL to make `buildAgent` task
// apply(from = "agent-tasks.gradle") // 暂时禁用：agent-tasks.gradle 文件不存在


// ============================================================
// BUILD DIRECTORY: 使用默认的 ${projectDir}/build 目录
// 原因：之前重定向到 D 盘导致 AGP 的 generateDebugRFile 任务配置路径与实际输出路径不一致
// （AGP 在子项目配置时直接捕获 buildDir，自定义路径会导致任务验证失败）
// 如需迁移到 D 盘，请通过环境变量 GRADLE_USER_HOME 和 -Dgradle.user.home 实现
// ============================================================