plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.20"
    id("com.google.cloud.tools.jib") version "3.4.5"
    application
}

group = "me.centralhardware"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

val ktorVersion = "3.3.2"
val tgbotapiVersion = "30.0.2"
val logbackVersion = "1.5.15"
val tesseractVersion = "5.12.0"

dependencies {
    // Telegram Bot API
    implementation("dev.inmo:tgbotapi:$tgbotapiVersion")
    implementation("com.github.centralhardware:ktgbotapi-commons:${tgbotapiVersion}")
    implementation("com.github.centralhardware.ktgbotapi-middlewars:ktgbotapi-restrict-access-middleware:${tgbotapiVersion}")

    // Ktor Client
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

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
    mainClass.set("me.centralhardware.forte2firefly.MainKt")
}

tasks.run.configure {
    systemProperty("jna.library.path", "/opt/homebrew/lib")
    environment("TESSDATA_PREFIX", "/opt/homebrew/share/tessdata")
}

jib {
    from {
        image = System.getenv("JIB_FROM_IMAGE") ?: "eclipse-temurin:24-jre"
    }
    to {
    }
    container {
        mainClass = "me.centralhardware.forte2firefly.MainKt"
        jvmFlags = listOf(
            "-XX:+UseContainerSupport",
            "-XX:MaxRAMPercentage=75.0",
            "--enable-native-access=ALL-UNNAMED"
        )
        creationTime = "USE_CURRENT_TIMESTAMP"
        labels = mapOf(
            "org.opencontainers.image.source" to (System.getenv("GITHUB_SERVER_URL")?.let { server ->
                val repo = System.getenv("GITHUB_REPOSITORY")
                if (repo != null) "$server/$repo" else ""
            } ?: ""),
            "org.opencontainers.image.revision" to (System.getenv("GITHUB_SHA") ?: "")
        )
    }
}