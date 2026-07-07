// ============================================================
// ⚠️ 模块归属声明（见 docs/architecture/MODULE_OWNERSHIP.md）
// ============================================================
// 本库（:lib:workflow）是 :apk:workflow 的私有库。
//
// 打包规则：
//   ✅ 只打包进 :apk:workflow
//   ❌ 不打包进 :app（主 APK）
//   ❌ 不打包进其他 :apk:*
//
// 原因：
//   - 工作流 DAG 编排（8 种节点类型 + 执行器）
//     只有工作流 APK 需要
//   - 其他 APK 通过 ApexClient.workflow.* 跨 APK 调用
// ============================================================

plugins {
    id("apex.android.library")
}

android {
    namespace = "com.apex.lib.workflow"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":sdk:common-core"))
    api(project(":sdk:process-bridge"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
