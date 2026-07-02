plugins {
    id("apex.android.library")
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

    // Parcelize for AIDL models
    implementation("org.jetbrains.kotlin:kotlin-parcelize:2.0.21")
}
