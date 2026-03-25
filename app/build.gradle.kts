
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 支持从命令行参数注入版本号（GitHub Actions Release 工作流使用）
// 例：./gradlew assembleRelease -PversionName=1.2.3 -PversionCode=10203
val ciVersionName: String = findProperty("versionName")?.toString() ?: "1.0.0"
val ciVersionCode: Int = findProperty("versionCode")?.toString()?.toIntOrNull() ?: 1

android {
    namespace = "com.example.android_sample"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.example.android_sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = ciVersionCode
        versionName = ciVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // 框架模块
    implementation(project(":framework-core"))
    implementation(project(":framework-crash"))
    implementation(project(":framework-logger"))
    implementation(project(":framework-network"))
    implementation(project(":framework-storage"))
    implementation(project(":framework-ui"))

    // 下拉刷新
    implementation(libs.androidx.swiperefreshlayout)

    // 图片加载
    implementation(libs.glide)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.mockk.android)
}

