package me.centralhardware.forte2firefly

import EnvironmentVariableUserAccessChecker
import dev.inmo.micro_utils.common.Warning
import dev.inmo.tgbotapi.AppConfig
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.longPolling
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.utils.RiskFeature
import me.centralhardware.forte2firefly.handlers.*
import me.centralhardware.forte2firefly.service.*
import org.slf4j.LoggerFactory
import restrictAccess

@OptIn(Warning::class, RiskFeature::class)
suspend fun main() {
    val logger = LoggerFactory.getLogger("Main")
    AppConfig.init("forte2firefly")
    
    try {
        startBot(logger)
    } catch (e: Exception) {
        logger.error("Fatal error", e)
        throw e
    }
}

suspend fun startBot(logger: org.slf4j.Logger) {
    val defaultCurrency = System.getenv("DEFAULT_CURRENCY") ?: "MYR"
    val currencyAccounts = mapOf(
        "USD" to (System.getenv("ACCOUNT_USD") ?: throw IllegalArgumentException("ACCOUNT_USD environment variable is not set")),
        "EUR" to (System.getenv("ACCOUNT_EUR") ?: throw IllegalArgumentException("ACCOUNT_EUR environment variable is not set")),
        "KZT" to (System.getenv("ACCOUNT_KZT") ?: throw IllegalArgumentException("ACCOUNT_KZT environment variable is not set"))
    )

    val parser = TransactionParser()
    val tessdataPath = System.getenv("TESSDATA_PREFIX") ?: "/usr/share/tesseract-ocr/5/tessdata/"
    val ocrService = OCRService(tessdataPath = tessdataPath)

    val longPolling = longPolling (
        middlewares = {
            addMiddleware { restrictAccess(EnvironmentVariableUserAccessChecker()) }
        }
    ) {
        registerMediaHandler(parser, ocrService, defaultCurrency, currencyAccounts)
        registerLocationHandler()
        registerTextHandler()
        registerBudgetHandler()
    }
    
    val bot = longPolling.first
    bot.setMyCommands(
        BotCommand("stats", "Show budget statistics for the current month")
    )
    
    longPolling.second.join()
}
