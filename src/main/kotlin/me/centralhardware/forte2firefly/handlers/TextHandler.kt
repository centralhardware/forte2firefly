package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.types.message.content.TextContent
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.service.FireflyApiClient
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun BehaviourContext.registerTextHandler() {
    onContentMessage(
        initialFilter = { it.content is TextContent }
    ) { message ->
        try {
            val text = (message.content as TextContent).text.trim()

            when {
                text.startsWith("/stats") || text.startsWith("/budget") -> {
                    generateBudgetStats(message.chat, bot)
                    return@onContentMessage
                }
            }

            val replyTo = message.replyTo
            if (replyTo != null) {
                @Suppress("UNCHECKED_CAST")
                handleTransactionUpdate(
                    message as CommonMessage<TextContent>,
                    replyTo,
                    bot
                )
            }
        } catch (e: Exception) {
            KSLog.error("Error processing text message", e)
            sendMessage(message.chat, "❌ Ошибка: ${e.message ?: "Неизвестная ошибка"}", linkPreviewOptions = LinkPreviewOptions.Disabled)
        }
    }
}

private suspend fun handleTransactionUpdate(
    message: CommonMessage<TextContent>,
    replyTo: Message,
    bot: TelegramBot
) {
    try {
        val newText = message.content.text.trim()
        val newAmount = newText.toDoubleOrNull()

        val replyContent = (replyTo as? dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<*>)?.content
        val textContent = when (replyContent) {
            is TextContent -> replyContent.text
            else -> {
                bot.sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении", linkPreviewOptions = LinkPreviewOptions.Disabled)
                return
            }
        }

        val transactionIdRegex = """(?:ID транзакции|ID):\s*(\d+)""".toRegex()
        val matchResult = transactionIdRegex.find(textContent)

        if (matchResult == null) {
            bot.sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении. Используйте reply на сообщение с ID транзакции.", linkPreviewOptions = LinkPreviewOptions.Disabled)
            return
        }

        val transactionId = matchResult.groupValues[1]

        val currentTransaction = FireflyApiClient.getTransaction(transactionId)
        val currentSplit = currentTransaction.data.attributes.transactions.first()
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        // Determine if this is an amount update or description update
        if (newAmount != null && newAmount > 0) {
            // Update amount
            bot.sendMessage(message.chat, "Обновляю сумму транзакции #$transactionId...", linkPreviewOptions = LinkPreviewOptions.Disabled)

            val oldAmount = currentSplit.amount
            val changeLog = "[$timestamp] Сумма изменена: ${oldAmount.formatAmount()} → ${newAmount.toBigDecimal().stripTrailingZeros().toPlainString()}"
            val updatedNotes = if (currentSplit.notes.isNullOrBlank()) {
                changeLog
            } else {
                "${currentSplit.notes}\n$changeLog"
            }

            val updatedSplit = currentSplit.copy(
                amount = newAmount.toString(),
                notes = updatedNotes
            )

            val updateRequest = TransactionRequest(
                transactions = listOf(updatedSplit)
            )

            FireflyApiClient.updateTransaction(transactionId, updateRequest)

            val successMessage = buildString {
                appendLine("✅ Сумма транзакции #$transactionId успешно обновлена")
                appendLine()
                appendLine("💰 Новая сумма: $newAmount")
                append("📝 ${currentSplit.description}")
            }

            bot.sendMessage(message.chat, successMessage, linkPreviewOptions = LinkPreviewOptions.Disabled)
        } else {
            // Update description
            if (newText.isBlank()) {
                bot.sendMessage(message.chat, "⚠️ Описание не может быть пустым.", linkPreviewOptions = LinkPreviewOptions.Disabled)
                return
            }

            bot.sendMessage(message.chat, "Обновляю описание транзакции #$transactionId...", linkPreviewOptions = LinkPreviewOptions.Disabled)

            val oldDescription = currentSplit.description
            val changeLog = "[$timestamp] Описание изменено: \"$oldDescription\" → \"$newText\""
            val updatedNotes = if (currentSplit.notes.isNullOrBlank()) {
                changeLog
            } else {
                "${currentSplit.notes}\n$changeLog"
            }

            val updatedSplit = currentSplit.copy(
                description = newText,
                notes = updatedNotes
            )

            val updateRequest = TransactionRequest(
                transactions = listOf(updatedSplit)
            )

            FireflyApiClient.updateTransaction(transactionId, updateRequest)

            val successMessage = buildString {
                appendLine("✅ Описание транзакции #$transactionId успешно обновлено")
                appendLine()
                appendLine("📝 Новое описание: $newText")
                append("💰 Сумма: ${currentSplit.amount.formatAmount()} ${currentSplit.currencyCode ?: ""}")
            }

            bot.sendMessage(message.chat, successMessage, linkPreviewOptions = LinkPreviewOptions.Disabled)
        }

    } catch (e: Exception) {
        KSLog.error("Error updating transaction", e)
        bot.sendMessage(message.chat, "❌ Ошибка при обновлении транзакции: ${e.message ?: "Неизвестная ошибка"}", linkPreviewOptions = LinkPreviewOptions.Disabled)
    }
}
