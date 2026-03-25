// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// 全局版本锁定：防止传递依赖（如 acra-notification）将 androidx.core / core-ktx 升级到 1.17+
// 注意：仅锁定 core 和 core-ktx，不能对 androidx.core group 整体匹配（core-viewtree 等新 artifact 不存在 1.15）
allprojects {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "androidx.core" &&
                (requested.name == "core" || requested.name == "core-ktx")) {
                useVersion("1.15.0")
                because("acra-notification pulls core 1.17 which requires compileSdk 36 / AGP 8.9.1")
            }
        }
    }
}

