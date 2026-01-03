package me.centralhardware.forte2firefly.handlers

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.types.message.content.TextContent
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.service.FireflyApiClient

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

            val replyTo = message.replyTo
            if (replyTo != null) {
                @Suppress("UNCHECKED_CAST")
                handleTransactionUpdate(
                    message as CommonMessage<TextContent>,
                    replyTo
                )
            }
        } catch (e: Exception) {
            KSLog.error("Error processing text message", e)
            sendMessage(message.chat, "❌ Ошибка: ${e.message ?: "Неизвестная ошибка"}", linkPreviewOptions = LinkPreviewOptions.Disabled)
        }
    }
}

private suspend fun BehaviourContext.handleTransactionUpdate(
    message: CommonMessage<TextContent>,
    replyTo: Message
) {
    try {
        val newText = message.content.text.trim()

        if (newText.isBlank()) {
            bot.sendMessage(message.chat, "⚠️ Описание не может быть пустым.", linkPreviewOptions = LinkPreviewOptions.Disabled)
            return
        }

        val replyContent = (replyTo as? dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<*>)?.content
        val textContent = when (replyContent) {
            is TextContent -> replyContent.text
            else -> {
                bot.sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении", linkPreviewOptions = LinkPreviewOptions.Disabled)
                return
            }
        }

        val idRegex = """ID транзакции:\s*(\d+),\s*Journal:\s*(\d+)""".toRegex()
        val matchResult = idRegex.find(textContent)
        
        if (matchResult == null) {
            bot.sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении. Используйте reply на сообщение с ID транзакции.", linkPreviewOptions = LinkPreviewOptions.Disabled)
            return
        }
        
        val transactionId = matchResult.groupValues[1]
        val journalId = matchResult.groupValues[2]

        bot.sendMessage(message.chat, "Обновляю описание транзакции #$transactionId...", linkPreviewOptions = LinkPreviewOptions.Disabled)

        val currentTransaction = FireflyApiClient.getTransaction(transactionId)
        val allSplits = currentTransaction.data.attributes.transactions

        val updatedSplits = allSplits.map { split ->
            if (split.transactionJournalId == journalId) {
                split.copy(description = newText)
            } else {
                split
            }
        }

        val updateRequest = TransactionRequest(
            transactions = updatedSplits
        )

        FireflyApiClient.updateTransaction(transactionId, updateRequest)

        val currentSplit = allSplits.find { it.transactionJournalId == journalId }
            ?: throw RuntimeException("Split with journal ID $journalId not found")

        val successMessage = buildString {
            appendLine("✅ Описание транзакции #$transactionId успешно обновлено")
            appendLine()
            appendLine("📝 Новое описание: $newText")
            append("💰 Сумма: ${currentSplit.amount.formatAmount()} ${currentSplit.currencyCode ?: ""}")
        }

        bot.sendMessage(message.chat, successMessage, linkPreviewOptions = LinkPreviewOptions.Disabled)

    } catch (e: Exception) {
        KSLog.error("Error updating transaction", e)
        bot.sendMessage(message.chat, "❌ Ошибка при обновлении транзакции: ${e.message ?: "Неизвестная ошибка"}", linkPreviewOptions = LinkPreviewOptions.Disabled)
    }
}
