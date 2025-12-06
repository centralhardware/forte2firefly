package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDocument
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onPhoto
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onVisualGallery
import me.centralhardware.forte2firefly.service.OCRService
import me.centralhardware.forte2firefly.service.TransactionParser
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error

fun BehaviourContext.registerMediaHandler(
    parser: TransactionParser,
    ocrService: OCRService
) {
    onPhoto { message ->
        try {
            val replyTo = message.replyTo
            if (replyTo != null) {
                handleAttachmentReply(message, replyTo)
                return@onPhoto
            }

            if (message.mediaGroupId == null) {
                sendMessage(message.chat, "Фото получено, обрабатываю...", linkPreviewOptions = LinkPreviewOptions.Disabled)
            }

            val photoBytes = bot.downloadFile(message.content)
            processPhotoTransaction(
                photoBytes = photoBytes,
                chatId = message.chat,
                parser = parser,
                ocrService = ocrService,
                bot = bot
            )

        } catch (e: Exception) {
            KSLog.error("Error processing photo", e)
            sendMessage(message.chat, "❌ Ошибка при обработке фото: ${e.message ?: "Неизвестная ошибка"}", linkPreviewOptions = LinkPreviewOptions.Disabled)
        }
    }

    onDocument { message ->
        try {
            val replyTo = message.replyTo
            if (replyTo != null) {
                handleAttachmentReply(message, replyTo)
                return@onDocument
            }

            sendMessage(message.chat, "⚠️ Чтобы прикрепить документ к транзакции, отправьте его как reply на сообщение с ID транзакции", linkPreviewOptions = LinkPreviewOptions.Disabled)
        } catch (e: Exception) {
            KSLog.error("Error processing document", e)
            sendMessage(message.chat, "❌ Ошибка при обработке документа: ${e.message ?: "Неизвестная ошибка"}", linkPreviewOptions = LinkPreviewOptions.Disabled)
        }
    }

    onVisualGallery { gallery ->
        val messages = gallery.group
        val totalCount = messages.size
        var successCount = 0
        var failedCount = 0

        messages.forEachIndexed { index, msg ->
            val currentNumber = index + 1
            val progress = "[$currentNumber/$totalCount] "

            try {
                val photoBytes = bot.downloadFile(msg.content)
                val msgChat = msg.sourceMessage.chat

                val transactionId = processPhotoTransaction(
                    photoBytes = photoBytes,
                    chatId = msgChat,
                    parser = parser,
                    ocrService = ocrService,
                    bot = bot,
                    progressPrefix = progress
                )
                if (transactionId != null) {
                    successCount++
                } else {
                    failedCount++
                }

            } catch (e: Exception) {
                KSLog.error("Error processing photo $progress from gallery", e)
                failedCount++
                sendMessage(msg.sourceMessage.chat, "${progress}❌ Ошибка: ${e.message ?: "Неизвестная ошибка"}", linkPreviewOptions = LinkPreviewOptions.Disabled)
            }
        }

        val finalMessage = buildString {
            appendLine("🏁 Обработка группы фото завершена")
            appendLine()
            appendLine("📊 Статистика:")
            appendLine("✅ Успешно: $successCount")
            if (failedCount > 0) {
                appendLine("❌ Ошибок: $failedCount")
            }
            append("📈 Всего: $totalCount")
        }

        if (messages.isNotEmpty()) {
            sendMessage(messages.first().sourceMessage.chat, finalMessage, linkPreviewOptions = LinkPreviewOptions.Disabled)
        }
    }
}
