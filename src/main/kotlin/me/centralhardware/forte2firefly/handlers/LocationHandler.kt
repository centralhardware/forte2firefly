package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onLocation
import dev.inmo.tgbotapi.types.message.content.TextContent
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.service.FireflyApiClient
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = LoggerFactory.getLogger("LocationHandler")

fun BehaviourContext.registerLocationHandler() {
    onLocation { message ->
        try {
            val replyTo = message.replyTo
            if (replyTo == null) {
                sendMessage(message.chat, "⚠️ Чтобы добавить локацию к транзакции, отправьте её как reply на сообщение с ID транзакции")
                return@onLocation
            }

            val replyContent = (replyTo as? dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<*>)?.content
            val textContent = when (replyContent) {
                is TextContent -> replyContent.text
                else -> {
                    sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении")
                    return@onLocation
                }
            }

            val transactionIdRegex = """(?:ID транзакции|ID):\s*(\d+)""".toRegex()
            val matchResult = transactionIdRegex.find(textContent)

            if (matchResult == null) {
                sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении. Используйте reply на сообщение с ID транзакции.")
                return@onLocation
            }

            val transactionId = matchResult.groupValues[1]
            val location = message.content.location

            sendMessage(message.chat, "Добавляю локацию к транзакции #$transactionId...")

            val currentTransaction = FireflyApiClient.getTransaction(transactionId)
            val currentSplit = currentTransaction.data.attributes.transactions.first()

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val changeLog = "[$timestamp] Локация добавлена: ${location.latitude}, ${location.longitude}"
            val updatedNotes = if (currentSplit.notes.isNullOrBlank()) {
                changeLog
            } else {
                "${currentSplit.notes}\n$changeLog"
            }

            val updatedSplit = currentSplit.copy(
                notes = updatedNotes,
                latitude = location.latitude,
                longitude = location.longitude,
                zoomLevel = 15
            )

            val updateRequest = TransactionRequest(
                transactions = listOf(updatedSplit)
            )

            FireflyApiClient.updateTransaction(transactionId, updateRequest)

            sendMessage(
                message.chat,
                "✅ Локация успешно добавлена к транзакции #$transactionId\n📍 ${location.latitude}, ${location.longitude}"
            )

        } catch (e: Exception) {
            logger.error("Error processing location", e)
            sendMessage(message.chat, "❌ Ошибка при добавлении локации: ${e.message ?: "Неизвестная ошибка"}")
        }
    }
}
