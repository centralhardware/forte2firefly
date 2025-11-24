package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.types.message.content.MediaContent
import dev.inmo.tgbotapi.types.message.content.TextContent
import me.centralhardware.forte2firefly.model.AttachmentRequest
import me.centralhardware.forte2firefly.service.FireflyApiClient
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = LoggerFactory.getLogger("AttachmentHandler")

suspend fun <T : MediaContent> handleAttachmentReply(
    message: CommonMessage<T>,
    replyTo: Message,
    fireflyClient: FireflyApiClient,
    bot: TelegramBot
) {
    try {
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
        bot.sendMessage(message.chat, "Прикрепляю файл к транзакции #$transactionId...")

        val transaction = fireflyClient.getTransaction(transactionId)
        val journalId = transaction.data.attributes.transactions.first().transactionJournalId
            ?: throw RuntimeException("Transaction journal ID is missing")
        val fileBytes = bot.downloadFile(message.content)

        val messageText = when (val content = message.content) {
            is dev.inmo.tgbotapi.types.message.content.PhotoContent -> content.text
            is dev.inmo.tgbotapi.types.message.content.DocumentContent -> content.text
            else -> null
        }?.trim()
        
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        val timestamp = LocalDateTime.now().format(formatter)
        
        val filename: String
        val title: String
        when (val content = message.content) {
            is dev.inmo.tgbotapi.types.message.content.PhotoContent -> {
                if (!messageText.isNullOrBlank()) {
                    filename = "$messageText.jpg"
                    title = messageText
                } else {
                    filename = "photo_$timestamp.jpg"
                    title = "Photo $timestamp"
                }
            }
            is dev.inmo.tgbotapi.types.message.content.DocumentContent -> {
                val originalName = content.media.fileName
                
                if (originalName != null) {
                    filename = originalName
                    title = messageText?.takeIf { it.isNotBlank() } ?: originalName
                } else {
                    val extension = originalName?.substringAfterLast('.', "")
                    if (!messageText.isNullOrBlank()) {
                        filename = if (!extension.isNullOrBlank()) {
                            "$messageText.$extension"
                        } else {
                            messageText
                        }
                        title = messageText
                    } else {
                        filename = if (!extension.isNullOrBlank()) {
                            "document_$timestamp.$extension"
                        } else {
                            "document_$timestamp"
                        }
                        title = "Document $timestamp"
                    }
                }
            }
            else -> {
                filename = if (!messageText.isNullOrBlank()) {
                    messageText
                } else {
                    "attachment_$timestamp"
                }
                title = messageText ?: "Attachment $timestamp"
            }
        }

        val attachmentRequest = AttachmentRequest(
            filename = filename,
            attachableType = "TransactionJournal",
            attachableId = journalId,
            title = title,
            notes = "Added via reply in Telegram Bot"
        )

        val attachmentResponse = fireflyClient.createAttachment(attachmentRequest)
        val uploadUrl = attachmentResponse.data.attributes.uploadUrl
        if (uploadUrl != null) {
            fireflyClient.uploadAttachment(uploadUrl, fileBytes)
        }

        bot.sendMessage(message.chat, "✅ Файл успешно прикреплен к транзакции #$transactionId")

    } catch (e: Exception) {
        logger.error("Error processing attachment reply", e)
        bot.sendMessage(message.chat, "❌ Ошибка при прикреплении файла: ${e.message ?: "Неизвестная ошибка"}")
    }
}
