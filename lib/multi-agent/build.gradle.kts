plugins {
    id("apex.android.library")
}

android {
    namespace = "com.apex.lib.multiagent"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":sdk:common-core"))
    api(project(":sdk:process-bridge"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
