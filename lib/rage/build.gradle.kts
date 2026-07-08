// ============================================================
// ⚠️ 模块归属声明（见 docs/architecture/MODULE_OWNERSHIP.md）
// ============================================================
// 本库（:lib:rage）是 :apk:rage 的私有库。
//
// 打包规则：
//   ✅ 只打包进 :apk:rage
//   ❌ 不打包进 :app（主 APK）
//   ❌ 不打包进其他 :apk:*
//
// 原因：
//   - 狂暴模式核心编排（4 Agent 架构师 + 31 技能目录 + 内存任务存储 + 引擎）
//     只有 Rage APK 需要
//   - 其他 APK 通过 ApexClient.rage.* 跨 APK 调用
// ============================================================

plugins {
    id("apex.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.lib.rage"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":sdk:common-core"))
    api(project(":sdk:process-bridge"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
