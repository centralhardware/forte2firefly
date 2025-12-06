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
import restrictAccess

@OptIn(Warning::class, RiskFeature::class)
suspend fun main() {
    AppConfig.init("forte2firefly")
    val parser = TransactionParser()
    val ocrService = OCRService(tessdataPath = Config.tessdataPrefix)

    longPolling (
        middlewares = {
            addMiddleware { restrictAccess(EnvironmentVariableUserAccessChecker()) }
        }
    ) {
        setMyCommands(
            BotCommand("stats", "Show budget statistics for the current month")
        )

        registerMediaHandler(parser, ocrService)
        registerTextHandler()
        registerBudgetHandler()
    }.second.join()
}
