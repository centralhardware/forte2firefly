plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "me.centralhardware"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.0.3"
val tgbotapiVersion = "24.0.0"
val logbackVersion = "1.5.15"
val tesseractVersion = "5.15.0"

dependencies {
    // Telegram Bot API
    implementation("dev.inmo:tgbotapi:$tgbotapiVersion")

    // Ktor Client
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logbackVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Tesseract OCR
    implementation("net.sourceforge.tess4j:tess4j:$tesseractVersion")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(24)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("jna.library.path", "/opt/homebrew/lib")
    environment("TESSDATA_PREFIX", "/opt/homebrew/share/tessdata")
}

application {
    mainClass.set("me.centralhardware.forte2firefly.TestOCRKt")
}

tasks.run.configure {
    systemProperty("jna.library.path", "/opt/homebrew/lib")
    environment("TESSDATA_PREFIX", "/opt/homebrew/share/tessdata")
}