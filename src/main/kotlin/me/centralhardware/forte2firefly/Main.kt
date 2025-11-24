package me.centralhardware.forte2firefly

import EnvironmentVariableUserAccessChecker
import dev.inmo.micro_utils.common.Warning
import dev.inmo.tgbotapi.AppConfig
import dev.inmo.tgbotapi.longPolling
import dev.inmo.tgbotapi.utils.RiskFeature
import me.centralhardware.forte2firefly.handlers.*
import me.centralhardware.forte2firefly.service.*
import org.slf4j.LoggerFactory
import restrictAccess

@OptIn(Warning::class, RiskFeature::class)
suspend fun main() {
    val logger = LoggerFactory.getLogger("Main")
    logger.info("=== Application starting ===")

    try {
        // Инициализация AppConfig из ktgbotapi-commons
        AppConfig.init("forte2firefly")

        startBot(logger)
    } catch (e: Exception) {
        logger.error("FATAL ERROR IN MAIN", e)
        e.printStackTrace()
        throw e
    }
}

suspend fun startBot(logger: org.slf4j.Logger) {
    try {
        // Загрузка конфигурации из переменных окружения
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

        logger.info("Bot initialized successfully, starting polling...")

        // Запуск бота через longPolling из ktgbotapi-commons
        longPolling (
            middlewares = {
                addMiddleware { restrictAccess(EnvironmentVariableUserAccessChecker()) }
            }
        ) {
            logger.info("Bot started successfully")

            // Регистрация обработчиков через extension функции
            registerMediaHandler(fireflyClient, parser, ocrService, defaultCurrency, currencyAccounts)
            registerLocationHandler(fireflyClient)
            registerTextHandler(fireflyClient)
            registerBudgetHandler(fireflyClient)
        }.second.join()

    } catch (e: Exception) {
        logger.error("FATAL ERROR IN START BOT", e)
        e.printStackTrace()
        throw e
    }
}
