plugins {
    id("apex.android.library")
}

android {
    namespace = "com.apex.lib.workingfiles"
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":sdk:common-core"))
    api(project(":sdk:process-bridge"))
    api(project(":file"))

    implementation(libs.kotlinx.coroutines.core)
}
