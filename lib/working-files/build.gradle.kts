// ============================================================
// ⚠️ 模块归属声明（见 docs/architecture/MODULE_OWNERSHIP.md）
// ============================================================
// 本库（:lib:working-files）是 :apk:working-files 的私有库。
//
// 打包规则：
//   ✅ 只打包进 :apk:working-files
//   ❌ 不打包进 :app（主 APK）
//   ❌ 不打包进其他 :apk:*
//
// 原因：
//   - 工作文件区的核心逻辑（快照/diff/分支/Agent流程/时间机器/冲突检测/回放）
//     体积较大（含 java-diff-utils 等依赖）
//   - 主 APK 不应承担这部分体积，按需安装工作文件 APK 即可
//   - 其他 APK 通过 ApexClient.workingFiles.* 跨 APK 调用，零延迟（同进程）
//
// 如需使用本库的能力，请通过：
//   ApexClient.workingFiles.startAgentSession(...)
//   ApexClient.workingFiles.writeWithSnapshot(...)
//   ApexClient.workingFiles.createBranch(...)
//   等 35+ 方法
// ============================================================

plugins {
    id("apex.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.lib.workingfiles"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":sdk:common-core"))
    api(project(":sdk:process-bridge"))
    api(project(":file"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.java.diff.utils)
}
