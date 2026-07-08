// ============================================================
// ⚠️ 模块归属声明（见 docs/architecture/MODULE_OWNERSHIP.md）
// ============================================================
// 本库（:lib:engine）是 :apk:engine 的私有库。
//
// 打包规则：
//   ✅ 只打包进 :apk:engine
//   ❌ 不打包进 :app（主 APK）
//   ❌ 不打包进其他 :apk:*
//
// 原因：
//   - 引擎编排逻辑（Shell/工具/容器/Shizuku 状态机 + 编排）
//     是 Engine APK 的私有领域层
//   - 顶层 :engine 模块（com.ai.assistance.apex.engine.*）提供真正的
//     EngineService / 工具 / 容器 / 无障碍 / Shizuku 实现，由 APK 直接依赖
//   - 本 lib 仅定义 EngineGateway 契约 + 编排逻辑，便于单测与解耦
//   - 其他 APK 通过 ApexClient.engine.* 跨 APK 调用
// ============================================================

plugins {
    id("apex.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.lib.engine"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":sdk:common-core"))
    api(project(":sdk:process-bridge"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
