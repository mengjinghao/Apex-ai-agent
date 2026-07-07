import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import java.io.File

/**
 * Apex 套件 APK 模块的 convention plugin。
 *
 * 在 [AndroidApplicationConventionPlugin] 基础上额外配置：
 * - 共享签名（来自 root project 的 apex.keystore，或 local.properties 中指定的 keystore）
 * - SharedUserId 占位（com.apex.agent.suite）
 * - 主进程名占位（com.apex.agent.mainprocess）
 * - 自动注入 SDK 依赖（common-core / process-bridge / watchdog / auth / storage）
 * - 自动注入 common-ui（如果模块启用了 Compose）
 *
 * 签名配置读取顺序（与主 app 模块一致）：
 *   1. local.properties 中的 RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD /
 *      RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
 *   2. 环境变量 APEX_RELEASE_STORE_FILE / APEX_RELEASE_STORE_PASSWORD /
 *      APEX_KEY_ALIAS / APEX_KEY_PASSWORD（CI 友好）
 *
 * 使用：
 *   plugins {
 *     id("apex.suite.apk")
 *     // 可选：id("apex.android.application.compose") 启用 Compose
 *   }
 */
class ApexSuiteApkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // 先应用基础 application convention
            pluginManager.apply("apex.android.application")

            // 自动应用模块归属校验（防止 lib:* 被错误打包进非授权 APK）
            pluginManager.apply("apex.module.ownership")

            // 注入套件级 SDK 依赖
            dependencies.apply {
                add("implementation", project(":sdk:common-core"))
                add("api", project(":sdk:process-bridge"))
                add("implementation", project(":sdk:watchdog"))
                add("implementation", project(":sdk:auth"))
                add("implementation", project(":sdk:storage"))
            }

            // 读取签名配置（local.properties 优先，环境变量兜底）
            val localProperties = java.util.Properties().apply {
                val file = rootProject.file("local.properties")
                if (file.exists()) load(file.inputStream())
            }
            val storeFile = localProperties.getProperty("RELEASE_STORE_FILE")
                ?: System.getenv("APEX_RELEASE_STORE_FILE")
            val storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                ?: System.getenv("APEX_RELEASE_STORE_PASSWORD")
            val keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                ?: System.getenv("APEX_KEY_ALIAS")
            val keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
                ?: System.getenv("APEX_KEY_PASSWORD")

            val hasValidSigning = storeFile != null &&
                storePassword != null &&
                keyAlias != null &&
                keyPassword != null &&
                File(storeFile).exists()

            // 配置 manifest placeholders + 签名
            extensions.configure<ApplicationExtension> {
                defaultConfig {
                    // 主进程名 — 所有 APK 共享，确保“零延迟”跨 APK 调用
                    manifestPlaceholders["apexMainProcess"] = "com.apex.agent.mainprocess"
                    manifestPlaceholders["apexSharedUserId"] = "com.apex.agent.suite"
                    // 套件级 Application 类（各 APK 可覆盖）
                    manifestPlaceholders["apkId"] = project.name.substringAfterLast(":")
                }

                // 统一配置 release 签名（所有套件 APK 共用同一 keystore）
                if (hasValidSigning && storeFile != null && storePassword != null && keyAlias != null && keyPassword != null) {
                    signingConfigs {
                        create("release") {
                            this.storeFile = File(storeFile)
                            this.storePassword = storePassword
                            this.keyAlias = keyAlias
                            this.keyPassword = keyPassword
                        }
                    }
                    buildTypes {
                        release {
                            signingConfig = signingConfigs.getByName("release")
                        }
                    }
                }
            }
        }
    }
}
