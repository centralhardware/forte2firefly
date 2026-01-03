package me.centralhardware.forte2firefly.handlers

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDocument
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onPhoto
import dev.inmo.tgbotapi.types.LinkPreviewOptions

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

}
