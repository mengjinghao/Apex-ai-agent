// ============================================================
// ⚠️ 模块归属声明（见 docs/architecture/MODULE_OWNERSHIP.md）
// ============================================================
// 本库（:lib:voice）是 :apk:voice 的私有库。
//
// 打包规则：
//   ✅ 只打包进 :apk:voice
//   ❌ 不打包进 :app（主 APK）
//   ❌ 不打包进其他 :apk:*
//
// 原因：
//   - 语音（TTS + ASR）会话管理、对话缓冲、引擎编排
//     只有 Voice APK 需要；具体的 Android TextToSpeech /
//     SpeechRecognizer 适配由 APK 侧实现 TtsGateway / AsrGateway
//   - 其他 APK 通过 ApexClient.voice.* 跨 APK 调用
// ============================================================

plugins {
    id("apex.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.lib.voice"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":sdk:common-core"))
    api(project(":sdk:process-bridge"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
