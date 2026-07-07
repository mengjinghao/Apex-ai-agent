// ============================================================
// ⚠️ 模块归属声明（见 docs/architecture/MODULE_OWNERSHIP.md）
// ============================================================
// 本库（:lib:market）是 :apk:market 的私有库。
//
// 打包规则：
//   ✅ 只打包进 :apk:market
//   ❌ 不打包进 :app（主 APK）
//   ❌ 不打包进其他 :apk:*
//
// 原因：
//   - 市场核心逻辑（目录注册 / 缓存 / 收藏 / 使用统计 /
//     安装状态机 / LLM 与 Skill 调用契约）只有 Market APK 需要
//   - 其他 APK 通过 ApexClient.market.* 跨 APK 调用
// ============================================================

plugins {
    id("apex.android.library")
}

android {
    namespace = "com.apex.lib.market"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":sdk:common-core"))
    api(project(":sdk:process-bridge"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
