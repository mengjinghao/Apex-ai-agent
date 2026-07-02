plugins {
    id("apex.android.library")
}

android {
    namespace = "com.apex.sdk.bridge"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    api(project(":sdk:common-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // For LocalSocket (Linux domain socket) — part of Android platform, no extra dep
}
