package me.centralhardware.forte2firefly

import dev.inmo.tgbotapi.bot.ktor.telegramBot
import kotlinx.coroutines.runBlocking
import me.centralhardware.forte2firefly.service.*
import org.slf4j.LoggerFactory

fun main() {
    val logger = LoggerFactory.getLogger("Main")
    logger.info("=== Application starting ===")

    try {
        runBlocking {
            startBot(logger)
        }
    } catch (e: Exception) {
        logger.error("FATAL ERROR IN MAIN", e)
        e.printStackTrace()
        throw e
    }
}

suspend fun startBot(logger: org.slf4j.Logger) {
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

        logger.info("Creating Telegram bot...")
        val bot = telegramBot(telegramBotToken)
        logger.info("Telegram bot created successfully")

        logger.info("Creating Firefly API client...")
        val fireflyClient = FireflyApiClient(fireflyBaseUrl, fireflyToken)
        logger.info("Firefly API client created successfully")

        logger.info("Creating transaction parser...")
        val parser = TransactionParser()
        logger.info("Transaction parser created successfully")

        // Tesseract data path - в Docker образе это /usr/share/tesseract-ocr/5/tessdata/
        val tessdataPath = System.getenv("TESSDATA_PREFIX") ?: "/usr/share/tesseract-ocr/5/tessdata/"
        logger.info("Using Tesseract data path: $tessdataPath")

        logger.info("Creating OCR service...")
        val ocrService = try {
            OCRService(tessdataPath = tessdataPath)
        } catch (e: Exception) {
            logger.error("FAILED TO CREATE OCR SERVICE", e)
            e.printStackTrace()
            throw e
        }
        logger.info("OCR service created successfully")

        logger.info("Creating bot handler...")
        val botHandler = TelegramBotHandler(
            bot = bot,
            fireflyClient = fireflyClient,
            parser = parser,
            ocrService = ocrService,
            defaultCurrency = defaultCurrency,
            currencyAccounts = currencyAccounts
        )
        logger.info("Bot handler created successfully")

        logger.info("Bot initialized successfully, starting polling...")

        // Запуск бота
        try {
            botHandler.start()
        } catch (e: Exception) {
            logger.error("ERROR DURING BOT POLLING", e)
            e.printStackTrace()
            throw e
        }

    } catch (e: Exception) {
        logger.error("FATAL ERROR IN START BOT", e)
        e.printStackTrace()
        throw e
    }
}
