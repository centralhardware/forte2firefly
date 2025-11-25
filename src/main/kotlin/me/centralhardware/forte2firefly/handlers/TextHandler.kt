package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.types.message.content.TextContent
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.service.FireflyApiClient
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = LoggerFactory.getLogger("TextHandler")

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
                handleAmountCorrection(
                    message as CommonMessage<TextContent>,
                    replyTo,
                    bot
                )
            }
        } catch (e: Exception) {
            logger.error("Error processing text message", e)
            sendMessage(message.chat, "❌ Ошибка: ${e.message ?: "Неизвестная ошибка"}")
        }
    }
}

private suspend fun handleAmountCorrection(
    message: CommonMessage<TextContent>,
    replyTo: Message,
    bot: TelegramBot
) {
    try {
        val newAmountText = message.content.text.trim()
        val newAmount = newAmountText.toDoubleOrNull()

        if (newAmount == null || newAmount <= 0) {
            bot.sendMessage(message.chat, "⚠️ Некорректная сумма. Введите положительное число.")
            return
        }

        val replyContent = (replyTo as? dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<*>)?.content
        val textContent = when (replyContent) {
            is TextContent -> replyContent.text
            else -> {
                bot.sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении")
                return
            }
        }

        val transactionIdRegex = """(?:ID транзакции|ID):\s*(\d+)""".toRegex()
        val matchResult = transactionIdRegex.find(textContent)

        if (matchResult == null) {
            bot.sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении. Используйте reply на сообщение с ID транзакции.")
            return
        }

        val transactionId = matchResult.groupValues[1]
        bot.sendMessage(message.chat, "Обновляю сумму транзакции #$transactionId...")

        val currentTransaction = FireflyApiClient.getTransaction(transactionId)
        val currentSplit = currentTransaction.data.attributes.transactions.first()
        val oldAmount = currentSplit.amount

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
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

        bot.sendMessage(message.chat, successMessage)

    } catch (e: Exception) {
        logger.error("Error correcting amount", e)
        bot.sendMessage(message.chat, "❌ Ошибка при обновлении суммы: ${e.message ?: "Неизвестная ошибка"}")
    }
}
