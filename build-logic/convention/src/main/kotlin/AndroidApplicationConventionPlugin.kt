import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import org.gradle.kotlin.dsl.configure

/**
 * Android Application 模块的 convention plugin。
 *
 * 应用此插件后，模块会自动获得：
 * - compileSdk = 35, minSdk = 26, targetSdk = 35
 * - Java 17 + Kotlin JvmTarget 17
 * - 默认的 ProGuard 配置
 * - 单 APK 输出（无 flavor）
 * - 统一的 buildConfig 开关
 * - Core library desugaring
 *
 * 使用：在 app/build.gradle.kts 中
 *   plugins { id("apex.android.application") }
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<ApplicationExtension> {
                compileSdk = 35

                defaultConfig {
                    minSdk = 26
                    targetSdk = 35

                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    vectorDrawables.useSupportLibrary = true
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                    isCoreLibraryDesugaringEnabled = true
                }

                buildFeatures {
                    buildConfig = true
                }

                buildTypes {
                    release {
                        isMinifyEnabled = false
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro"
                        )
                    }
                }

                packaging {
                    resources {
                        excludes += "/META-INF/{AL2.0,LGPL2.1}"
                        excludes += "META-INF/INDEX.LIST"
                        excludes += "META-INF/io.netty.versions.properties"
                    }
                }
            }

            target.dependencies.add("coreLibraryDesugaring", "com.android.tools:desugar_jdk_libs:2.0.3")
        }
    }
}
