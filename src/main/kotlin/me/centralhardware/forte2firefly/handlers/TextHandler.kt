package me.centralhardware.forte2firefly.handlers

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.message.content.TextContent

fun BehaviourContext.registerTextHandler() {
    onContentMessage(
        initialFilter = { it.content is TextContent }
    ) { message ->
        try {
            val text = (message.content as TextContent).text.trim()

            when {
                text.startsWith("/stats") || text.startsWith("/budget") -> {
                    generateBudgetStats(message.chat)
                    return@onContentMessage
                }
            }
        } catch (e: Exception) {
            KSLog.error("Error processing text message", e)
            sendMessage(message.chat, "❌ Ошибка: ${e.message ?: "Неизвестная ошибка"}", linkPreviewOptions = LinkPreviewOptions.Disabled)
        }
    }
}
