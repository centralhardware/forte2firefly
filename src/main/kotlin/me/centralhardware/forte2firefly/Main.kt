package me.centralhardware.forte2firefly

import dev.inmo.tgbotapi.bot.ktor.telegramBot
import kotlinx.coroutines.runBlocking
import me.centralhardware.forte2firefly.service.*
import org.slf4j.LoggerFactory

fun main() = runBlocking {
    val logger = LoggerFactory.getLogger("Main")

    try {
        // Загрузка конфигурации из переменных окружения
        val telegramBotToken = System.getenv("TELEGRAM_BOT_TOKEN")
            ?: throw IllegalArgumentException("TELEGRAM_BOT_TOKEN environment variable is not set")

        val fireflyBaseUrl = System.getenv("FIREFLY_BASE_URL")
            ?: throw IllegalArgumentException("FIREFLY_BASE_URL environment variable is not set")

        val fireflyToken = System.getenv("FIREFLY_TOKEN")
            ?: throw IllegalArgumentException("FIREFLY_TOKEN environment variable is not set")

        val defaultCurrency = System.getenv("DEFAULT_CURRENCY") ?: "MYR"

        // Мапа валюта -> имя счета в Firefly (только для основных валют транзакций)
        val currencyAccounts = mapOf(
            "USD" to (System.getenv("ACCOUNT_USD") ?: throw IllegalArgumentException("ACCOUNT_USD environment variable is not set")),
            "EUR" to (System.getenv("ACCOUNT_EUR") ?: throw IllegalArgumentException("ACCOUNT_EUR environment variable is not set")),
            "KZT" to (System.getenv("ACCOUNT_KZT") ?: throw IllegalArgumentException("ACCOUNT_KZT environment variable is not set"))
        )

        logger.info("Starting Forte2Firefly Telegram Bot")
        logger.info("Firefly URL: $fireflyBaseUrl")
        logger.info("Default currency: $defaultCurrency")
        logger.info("Configured accounts: ${currencyAccounts.keys.joinToString(", ")}")

        // Инициализация сервисов
        val bot = telegramBot(telegramBotToken)
        val fireflyClient = FireflyApiClient(fireflyBaseUrl, fireflyToken)
        val parser = TransactionParser()
        val ocrService = OCRService()

        val botHandler = TelegramBotHandler(
            bot = bot,
            fireflyClient = fireflyClient,
            parser = parser,
            ocrService = ocrService,
            defaultCurrency = defaultCurrency,
            currencyAccounts = currencyAccounts
        )

        logger.info("Bot initialized successfully, starting polling...")
        
        // Запуск бота
        botHandler.start()

    } catch (e: Exception) {
        logger.error("Fatal error", e)
        throw e
    }
}
