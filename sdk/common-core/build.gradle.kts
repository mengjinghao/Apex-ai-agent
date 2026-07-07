plugins {
    id("apex.android.library")
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.apex.sdk.common"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
