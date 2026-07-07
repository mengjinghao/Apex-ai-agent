plugins {
    id("apex.android.library")
    id("apex.android.library.compose")
}

android {
    namespace = "com.apex.sdk.ui"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    api(project(":sdk:common-core"))

    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.ui.tooling.preview)
    api(libs.androidx.compose.material3)
    api(libs.androidx.activity.compose)
    api(libs.androidx.compose.material.icons.extended)

    api(libs.androidx.core.ktx)
    api(libs.androidx.appcompat)
    api(libs.google.material)

    debugApi(libs.androidx.compose.ui.tooling)
}
