package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.types.message.content.DocumentContent
import dev.inmo.tgbotapi.types.message.content.MediaContent
import dev.inmo.tgbotapi.types.message.content.PhotoContent
import dev.inmo.tgbotapi.types.message.content.TextContent
import me.centralhardware.forte2firefly.service.FireflyApiClient
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

suspend fun <T : MediaContent> BehaviourContext.handleAttachmentReply(
    message: CommonMessage<T>,
    replyTo: Message
) {
    try {
        val replyContent = (replyTo as? ContentMessage<*>)?.content
        val textContent = when (replyContent) {
            is TextContent -> replyContent.text
            else -> {
                sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении", linkPreviewOptions = LinkPreviewOptions.Disabled)
                return
            }
        }

        val transactionIdRegex = """(?:ID транзакции|ID):\s*(\d+)""".toRegex()
        val matchResult = transactionIdRegex.find(textContent)
        
        if (matchResult == null) {
            sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении. Используйте reply на сообщение с ID транзакции.", linkPreviewOptions = LinkPreviewOptions.Disabled)
            return
        }

        val transactionId = matchResult.groupValues[1]
        sendMessage(message.chat, "Прикрепляю файл к транзакции #$transactionId...", linkPreviewOptions = LinkPreviewOptions.Disabled)

        val transaction = FireflyApiClient.getTransaction(transactionId)
        val journalId = transaction.data.attributes.transactions.first().transactionJournalId
            ?: throw RuntimeException("Transaction journal ID is missing")
        val fileBytes = downloadFile(message.content)

        val messageText = when (val content = message.content) {
            is PhotoContent -> content.text
            is DocumentContent -> content.text
            else -> null
        }?.trim()
        
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        val timestamp = LocalDateTime.now().format(formatter)
        
        val filename: String
        val title: String
        when (val content = message.content) {
            is PhotoContent -> {
                if (!messageText.isNullOrBlank()) {
                    filename = "$messageText.jpg"
                    title = messageText
                } else {
                    filename = "photo_$timestamp.jpg"
                    title = "Photo $timestamp"
                }
            }
            is DocumentContent -> {
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

        FireflyApiClient.createAndUploadAttachment(
            transactionJournalId = journalId,
            filename = filename,
            title = title,
            fileBytes = fileBytes,
            notes = "Added via reply in Telegram Bot"
        )

        sendMessage(message.chat, "✅ Файл успешно прикреплен к транзакции #$transactionId", linkPreviewOptions = LinkPreviewOptions.Disabled)

    } catch (e: Exception) {
        KSLog.error("Error processing attachment reply", e)
        sendMessage(message.chat, "❌ Ошибка при прикреплении файла: ${e.message ?: "Неизвестная ошибка"}", linkPreviewOptions = LinkPreviewOptions.Disabled)
    }
}
