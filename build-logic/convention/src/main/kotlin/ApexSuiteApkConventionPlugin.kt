import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

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
 * 使用：
 *   plugins {
 *     id("apex.suite.apk")
 *     // 可选：id("apex.android.application.compose") 启用 Compose
 *   }
 *
 * 然后在每个 APK 的 AndroidManifest.xml 中：
 *   <manifest ... package="...">
 *     <application android:process="${apexMainProcess}" ...>
 *       <activity android:name=".MainActivity" android:process="${apexMainProcess}" />
 *       <service  android:name=".XService" android:process="${apexMainProcess}" />
 *     </application>
 *   </manifest>
 *
 * **关于 SharedUserId 的现代 Android 兼容性**：
 *   - Android 10+ (API 29+) 已废弃 android:sharedUserId，新应用无法使用
 *   - 但本架构真正依赖的是 android:process（同进程 = 共享 JVM = 零延迟），
 *     sharedUserId 只是兼容旧设备时的可选优化
 *   - 现代权限共享通过 PermissionBridge（路由到主 APK 申请）实现
 */
class ApexSuiteApkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // 先应用基础 application convention
            pluginManager.apply("apex.android.application")

            // 注入套件级 SDK 依赖
            dependencies.apply {
                add("implementation", project(":sdk:common-core"))
                add("implementation", project(":sdk:process-bridge"))
                add("implementation", project(":sdk:watchdog"))
                add("implementation", project(":sdk:auth"))
                add("implementation", project(":sdk:storage"))
            }

            // 配置 manifest placeholders
            extensions.configure<ApplicationExtension> {
                defaultConfig {
                    // 主进程名 — 所有 APK 共享，确保“零延迟”跨 APK 调用
                    manifestPlaceholders["apexMainProcess"] = "com.apex.agent.mainprocess"
                    manifestPlaceholders["apexSharedUserId"] = "com.apex.agent.suite"
                    // 套件级 Application 类（各 APK 可覆盖）
                    manifestPlaceholders["apkId"] = project.name.substringAfterLast(":")
                }
            }
        }
    }
}
