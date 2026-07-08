// ============================================================
// ⚠️ 模块归属声明（见 docs/architecture/MODULE_OWNERSHIP.md）
// ============================================================
// 本库（:lib:multi-agent）是 :apk:multi-agent 的私有库。
//
// 打包规则：
//   ✅ 只打包进 :apk:multi-agent
//   ❌ 不打包进 :app（主 APK）
//   ❌ 不打包进其他 :apk:*
//
// 原因：
//   - 多 Agent 协作引擎（角色分工 + 黑板 + 5 种协作模式）
//     只有多 Agent APK 需要
//   - 其他 APK 通过 ApexClient.multiAgent.* 跨 APK 调用
// ============================================================

plugins {
    id("apex.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.lib.multiagent"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":sdk:common-core"))
    api(project(":sdk:process-bridge"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
