plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt") // For Room annotation processor
}

android {
    namespace = "com.arms.androidauto.core.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

kotlin {
    jvmToolchain(17)
}

// 플레이리스트 DB는 스키마를 파일로 남긴다. 나중에 버전을 올릴 때
// MigrationTestHelper로 마이그레이션을 자동 검증할 수 있게 하기 위함.
kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    testImplementation(kotlin("test"))

    // DAO 동작(순서 유지, 중복 무시, 연쇄 삭제)은 실제 SQLite에서만 검증할 수 있어
    // 계측 테스트로 확인한다.
    androidTestImplementation(kotlin("test"))
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
}
