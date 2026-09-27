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
    // HiveMQ는 WebSocket(ws/wss) 코덱을 선택 의존성으로 둔다. 없으면 접속 시 NoClassDefFoundError.
    implementation("io.netty:netty-codec-http:4.1.99.Final")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

// 실제 브로커 상대 통합 테스트용 CLI(RemoteCli.kt).
//   ./gradlew :core:remote:cli --args="gen --broker wss://host/mqtt --user u --pass p"
//   ./gradlew :core:remote:cli --args="host --pairing <qrtext>"
//   ./gradlew :core:remote:cli --args="guest --pairing <qrtext> --cmd play"
tasks.register<JavaExec>("cli") {
    group = "verification"
    description = "Run the remote-control host/guest CLI against a real MQTT broker"
    mainClass.set("com.arms.androidauto.core.remote.RemoteCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}
