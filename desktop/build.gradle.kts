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
    application { mainClass = "com.arms.androidauto.desktop.MainKt" }
}
