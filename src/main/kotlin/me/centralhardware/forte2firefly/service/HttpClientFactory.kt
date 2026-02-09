package me.centralhardware.forte2firefly.service

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import me.centralhardware.forte2firefly.Config

object HttpClientFactory {

    /**
     * Создает HTTP клиент с базовой конфигурацией и возможностью дополнительной настройки
     */
    fun createClient(config: HttpClientConfig<*>.() -> Unit = {}): HttpClient {
        return HttpClient(CIO) {
            // Базовая конфигурация для всех клиентов
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = false
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 10000
            }
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.INFO
            }

            // Дополнительная конфигурация
            config()
        }
    }

    /**
     * Общий HTTP клиент для REST Countries API и Exchange Rate API
     */
    val defaultClient: HttpClient by lazy {
        createClient()
    }

    /**
     * HTTP клиент для Firefly III с предустановленными заголовками и base URL
     */
    val fireflyClient: HttpClient by lazy {
        createClient {
            install(DefaultRequest) {
                url(Config.fireflyBaseUrl)
                header("Authorization", "Bearer ${Config.fireflyToken}")
                header("Accept", "application/vnd.api+json")
                contentType(ContentType.Application.Json)
            }
        }
    }
}
