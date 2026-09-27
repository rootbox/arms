plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.json:json:20260522")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // MQTT(WSS) 클라이언트. 자동 재접속·MQTT 3.1.1/5 지원. 브로커는 암호문만 본다.
    implementation("com.hivemq:hivemq-mqtt-client:1.3.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
