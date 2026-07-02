plugins {
    id("apex.android.library")
}

android {
    namespace = "com.apex.sdk.auth"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":sdk:common-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
