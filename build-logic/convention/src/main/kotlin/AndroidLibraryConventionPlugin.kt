import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.JavaVersion
import org.gradle.kotlin.dsl.configure

/**
 * Android Library 模块的 convention plugin。
 *
 * 应用此插件后，Library 模块会自动获得：
 * - compileSdk = 35, minSdk = 26
 * - Java 17 + Kotlin JvmTarget 17
 * - consumerProguardFiles 配置
 * - 统一的 packaging excludes
 *
 * 使用：在 <module>/build.gradle.kts 中
 *   plugins { id("apex.android.library") }
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.library")
                apply("org.jetbrains.kotlin.android")
            }

            extensions.configure<LibraryExtension> {
                compileSdk = 35

                defaultConfig {
                    minSdk = 26
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    consumerProguardFiles("consumer-rules.pro")
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
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
        }
    }
}
