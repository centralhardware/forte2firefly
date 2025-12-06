package me.centralhardware.forte2firefly

import EnvironmentVariableUserAccessChecker
import dev.inmo.micro_utils.common.Warning
import dev.inmo.tgbotapi.AppConfig
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.longPolling
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.utils.RiskFeature
import me.centralhardware.forte2firefly.handlers.*
import restrictAccess

@OptIn(Warning::class, RiskFeature::class)
suspend fun main() {
    AppConfig.init("forte2firefly")

    longPolling (
        middlewares = {
            addMiddleware { restrictAccess(EnvironmentVariableUserAccessChecker()) }
        }
    ) {
        setMyCommands(
            BotCommand("stats", "Show budget statistics for the current month")
        )

        registerMediaHandler()
        registerTextHandler()
        registerBudgetHandler()
    }.second.join()
}
