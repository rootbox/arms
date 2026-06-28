plugins {
    application
    kotlin("jvm")
}

application {
    mainClass.set("com.arms.androidauto.testapp.cli.MainKt")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("javazoom:jlayer:1.0.1") // Pure Java MP3 Player for actual streaming test
}

kotlin {
    jvmToolchain(17)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
