import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Android Application + Compose 模块的 convention plugin。
 *
 * 在 [AndroidApplicationConventionPlugin] 基础上额外启用 Compose。
 *
 * 注意：本项目已删除所有 UI 文件，此插件保留供未来恢复 UI 时使用。
 * 当前 app 模块即使不应用此插件也能正常构建（无 UI 输出纯逻辑 APK）。
 */
class AndroidApplicationComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("apex.android.application")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<ApplicationExtension> {
                buildFeatures {
                    compose = true
                }
            }

            dependencies {
                add("implementation", platform("androidx.compose:compose-bom:2024.06.00"))
                add("implementation", "androidx.compose.ui:ui")
                add("implementation", "androidx.compose.ui:ui-graphics")
                add("implementation", "androidx.compose.ui:ui-tooling-preview")
                add("implementation", "androidx.compose.material3:material3")
                add("implementation", "androidx.activity:activity-compose:1.8.2")
                add("implementation", "androidx.navigation:navigation-compose:2.5.3")
                add("debugImplementation", "androidx.compose.ui:ui-tooling")
            }
        }
    }
}
