plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arms.androidauto.core.streaming"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model")) // 추후 필요 시 추가
    testImplementation(kotlin("test"))
}
