plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:playback"))
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("org.json:json:20260522")
    testImplementation(kotlin("test"))
}

application {
    // 헤드리스 스모크 진입점 (Phase 2 검증용). Phase 3에서 Compose 데스크톱 UI로 대체.
    mainClass.set("com.arms.androidauto.desktop.SmokeKt")
}
