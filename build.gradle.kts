plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    id("com.google.cloud.tools.jib") version "3.5.3"
    application
}

group = "me.centralhardware"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

val ktorVersion = "3.4.0"
val tgbotapiVersion = "30.0.2"
val kslogVersion = "1.6.0"
val tesseractVersion = "5.18.0"

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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // Logging
    implementation("dev.inmo:kslog:$kslogVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Tesseract OCR
    implementation("net.sourceforge.tess4j:tess4j:$tesseractVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("jna.library.path", "/opt/homebrew/lib")
    systemProperty("java.awt.headless", "true")
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
        image = System.getenv("JIB_FROM_IMAGE") ?: "eclipse-temurin:25-jre"
    }
    to {
    }
    container {
        mainClass = "me.centralhardware.forte2firefly.MainKt"
        jvmFlags = listOf(
            "-XX:MaxRAMPercentage=75.0",
            "--enable-native-access=ALL-UNNAMED"
        )
        environment = mapOf(
            "TZ" to "Asia/Kuala_Lumpur"
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