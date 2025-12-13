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

fun BehaviourContext.registerMediaHandler() {
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
                chatId = message.chat
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

        // Обрабатываем только 2 или более фотографий как split транзакцию
        if (totalCount < 2) {
            sendMessage(
                messages.first().sourceMessage.chat,
                "⚠️ Для создания split транзакции необходимо отправить минимум 2 фотографии.\nПолучено: $totalCount",
                linkPreviewOptions = LinkPreviewOptions.Disabled
            )
            return@onVisualGallery
        }

        try {
            sendMessage(
                messages.first().sourceMessage.chat,
                "📸 Получено $totalCount фотографий, создаю split транзакцию...",
                linkPreviewOptions = LinkPreviewOptions.Disabled
            )

            // Загружаем все фотографии
            val photoBytes = messages.map { bot.downloadFile(it.content) }
            val chatId = messages.first().sourceMessage.chat

            processSplitTransaction(
                photoBytes = photoBytes,
                chatId = chatId
            )

        } catch (e: Exception) {
            KSLog.error("Error processing split transaction from gallery", e)
            sendMessage(
                messages.first().sourceMessage.chat,
                "❌ Ошибка при создании split транзакции: ${e.message ?: "Неизвестная ошибка"}",
                linkPreviewOptions = LinkPreviewOptions.Disabled
            )
        }
    }
}
