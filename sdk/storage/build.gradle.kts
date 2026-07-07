plugins {
    id("apex.android.library")
}

android {
    namespace = "com.apex.sdk.storage"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":sdk:common-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
}
