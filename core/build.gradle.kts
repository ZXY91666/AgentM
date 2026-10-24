plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.agentm.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    api(libs.okhttp)
    api(libs.okhttp.sse)
    api(libs.kotlinx.serialization.json)
    api(libs.coroutines.android)

    // core/ui/ 下的弹簧动画组件依赖 Compose
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.animation)
    api(libs.compose.material3)
    api("androidx.compose.foundation:foundation")

    testImplementation(libs.junit)
}
