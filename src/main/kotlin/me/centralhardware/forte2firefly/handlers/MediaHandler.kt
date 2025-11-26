package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDocument
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onPhoto
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onVisualGallery
import me.centralhardware.forte2firefly.service.FireflyApiClient
import me.centralhardware.forte2firefly.service.OCRService
import me.centralhardware.forte2firefly.service.TransactionParser
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("MediaHandler")

/**
 * Регистрирует обработчики для всех типов медиа-контента (фото, документы)
 * Унифицированная логика:
 * - Если это reply на сообщение с ID транзакции - прикрепляем файл к транзакции
 * - Если это фото (не reply) - распознаём через OCR и создаём транзакцию
 * - Если это документ (не reply) - просим отправить как reply
 */
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

            // Создаём новую транзакцию из фото через OCR
            if (message.mediaGroupId == null) {
                sendMessage(message.chat, "Фото получено, обрабатываю...")
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
            logger.error("Error processing photo", e)
            sendMessage(message.chat, "❌ Ошибка при обработке фото: ${e.message ?: "Неизвестная ошибка"}")
        }
    }

    // Обработка документов
    onDocument { message ->
        try {
            val replyTo = message.replyTo
            if (replyTo != null) {
                handleAttachmentReply(message, replyTo)
                return@onDocument
            }

            // Документ без reply - просим отправить как reply
            sendMessage(message.chat, "⚠️ Чтобы прикрепить документ к транзакции, отправьте его как reply на сообщение с ID транзакции")
        } catch (e: Exception) {
            logger.error("Error processing document", e)
            sendMessage(message.chat, "❌ Ошибка при обработке документа: ${e.message ?: "Неизвестная ошибка"}")
        }
    }

    // Обработка галереи фото
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
                logger.error("Error processing photo $progress from gallery", e)
                failedCount++
                sendMessage(msg.sourceMessage.chat, "${progress}❌ Ошибка: ${e.message ?: "Неизвестная ошибка"}")
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
            sendMessage(messages.first().sourceMessage.chat, finalMessage)
        }
    }
}
