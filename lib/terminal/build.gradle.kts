// ============================================================
// ⚠️ 模块归属声明（见 docs/architecture/MODULE_OWNERSHIP.md）
// ============================================================
// 本库（:lib:terminal）是 :apk:terminal 的私有库。
//
// 打包规则：
//   ✅ 只打包进 :apk:terminal
//   ❌ 不打包进 :app（主 APK）
//   ❌ 不打包进其他 :apk:*
//
// 原因：
//   - 终端会话管理 / 命令历史 / 输出缓冲 / PTY 网关契约属于终端领域逻辑
//     （含三块结构：普通 / 多 Agent / 狂暴），只有终端 APK 需要
//   - :ai-terminal 提供底层 C++ PTY + ApexTerminal 门面，
//     :lib:terminal 在其之上抽象出纯领域层（无 Android 依赖）
//   - 其他 APK 通过 ApexClient.terminal.* 跨 APK 调用
// ============================================================

plugins {
    id("apex.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.lib.terminal"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":sdk:common-core"))
    api(project(":sdk:process-bridge"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
