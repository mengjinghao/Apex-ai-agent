plugins {
    id("apex.suite.apk")
    id("apex.android.application.compose")
}

android {
    namespace = "com.apex.apk.engine"

    defaultConfig {
        applicationId = "com.apex.apk.engine"
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
        aidl = true
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
}

dependencies {
    // SDK deps auto-injected by apex.suite.apk convention plugin
    implementation(project(":sdk:common-ui"))
    implementation(project(":engine"))
}
