import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose") version "1.6.11"
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:playback"))
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("org.json:json:20260522")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "com.arms.androidauto.desktop.MainKt"
        nativeDistributions {
            // macOS = .dmg, Windows = .msi. 각 OS 러너에서 자기 포맷만 만들어진다.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "SimpleRadio"
            packageVersion = "1.0.0"
            description = "Korean radio, 24/7 K-POP, and NAS music player"
            vendor = "1319.space"
            // ⚠️ libVLC(재생 엔진)는 번들하지 않는다(Phase 4a). 실행 기기에 VLC가 설치돼 있어야
            // NativeDiscovery가 libVLC를 찾는다. 자체포함 번들은 용량이 크고 OS별 검증이 필요해
            // Phase 4b로 남긴다. 미설치 시 앱은 뜨지만 재생에서 안내 메시지를 낸다.
            macOS { /* iconFile.set(...) // 아이콘 준비 시 지정 */ }
            windows { /* iconFile.set(...) */ }
        }
    }
}

// 헤드리스 재생 스모크(창 없이 5개 채널을 VLCJ로 실제 재생). `./gradlew :desktop:smoke`
tasks.register<JavaExec>("smoke") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.arms.androidauto.desktop.SmokeKt")
}
