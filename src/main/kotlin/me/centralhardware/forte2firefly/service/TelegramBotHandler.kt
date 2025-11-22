package me.centralhardware.forte2firefly.service

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onLocation
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDocument
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onPhoto
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onVisualGallery
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.content.MediaContent
import dev.inmo.tgbotapi.types.message.content.TextContent
import dev.inmo.tgbotapi.utils.extensions.escapeMarkdownV2Common
import dev.inmo.tgbotapi.utils.matrix
import me.centralhardware.forte2firefly.model.AttachmentRequest
import me.centralhardware.forte2firefly.model.Budget
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.model.TransactionSplit
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TelegramBotHandler(
    private val bot: TelegramBot,
    private val fireflyClient: FireflyApiClient,
    private val parser: TransactionParser,
    private val ocrService: OCRService,
    private val defaultCurrency: String = "MYR",
    private val currencyAccounts: Map<String, String>
) {
    private val logger = LoggerFactory.getLogger(TelegramBotHandler::class.java)

    private fun createBudgetKeyboard(transactionId: String, currentBudget: Budget): InlineKeyboardMarkup {
        return InlineKeyboardMarkup(
            keyboard = listOf(
                listOf(
                    CallbackDataInlineKeyboardButton(
                        text = "${currentBudget.emoji} ${currentBudget.budgetName}",
                        callbackData = "budget:$transactionId:${currentBudget.budgetName}"
                    )
                )
            )
        )
    }

    private suspend fun <T : MediaContent> handleAttachmentReply(
        message: dev.inmo.tgbotapi.types.message.abstracts.CommonMessage<T>,
        replyTo: dev.inmo.tgbotapi.types.message.abstracts.Message
    ) {
        try {
            val replyText = (replyTo as? dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<*>)?.content
            val textContent = when (replyText) {
                is dev.inmo.tgbotapi.types.message.content.TextContent -> replyText.text
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
            bot.sendMessage(message.chat, "Прикрепляю фото к транзакции #$transactionId...")

            val transaction = fireflyClient.getTransaction(transactionId)
            val journalId = transaction.data.attributes.transactions.first().transactionJournalId
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
            bot.sendMessage(message.chat, "❌ Ошибка при прикреплении фото: ${e.message ?: "Неизвестная ошибка"}")
        }
    }

    private suspend fun handleAmountCorrection(
        message: dev.inmo.tgbotapi.types.message.abstracts.CommonMessage<TextContent>,
        replyTo: dev.inmo.tgbotapi.types.message.abstracts.Message
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
                is dev.inmo.tgbotapi.types.message.content.TextContent -> replyContent.text
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

            val currentTransaction = fireflyClient.getTransaction(transactionId)
            val currentSplit = currentTransaction.data.attributes.transactions.first()
            val oldAmount = currentSplit.amount

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val changeLog = "[$timestamp] Сумма изменена: $oldAmount → $newAmount"
            val updatedNotes = if (currentSplit.notes.isNullOrBlank()) {
                changeLog
            } else {
                "${currentSplit.notes}\n$changeLog"
            }

            val updatedSplit = TransactionSplit(
                type = currentSplit.type,
                date = currentSplit.date,
                amount = newAmount.toString(),
                description = currentSplit.description,
                sourceName = currentSplit.sourceName,
                destinationName = currentSplit.destinationName,
                currencyCode = currentSplit.currencyCode ?: defaultCurrency,
                foreignAmount = currentSplit.foreignAmount,
                foreignCurrencyCode = currentSplit.foreignCurrencyCode,
                externalId = currentSplit.externalId,
                notes = updatedNotes,
                tags = currentSplit.tags,
                budgetId = currentSplit.budgetId,
                budgetName = currentSplit.budgetName,
                latitude = currentSplit.latitude,
                longitude = currentSplit.longitude,
                zoomLevel = currentSplit.zoomLevel
            )

            val updateRequest = TransactionRequest(
                transactions = listOf(updatedSplit)
            )

            fireflyClient.updateTransaction(transactionId, updateRequest)

            if (replyContent is dev.inmo.tgbotapi.types.message.content.TextContent &&
                replyTo is dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<*>) {
                val originalMessage = replyContent.text
                val updatedMessage = buildString {
                    append(originalMessage)
                    appendLine()
                    appendLine()
                    append("💰 Сумма изменена: $oldAmount → $newAmount")
                }

                try {
                    @Suppress("UNCHECKED_CAST")
                    bot.edit(
                        replyTo as dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<TextContent>,
                        updatedMessage
                    )
                } catch (e: Exception) {
                    logger.warn("Could not edit original message: ${e.message}")
                }
            }

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

    suspend fun start() {
        val botInfo = bot.getMe()
        logger.info("Bot started: @${botInfo.username}")

        bot.buildBehaviourWithLongPolling {
            onPhoto { message ->
                try {
                    val replyTo = message.replyTo
                    if (replyTo != null) {
                        handleAttachmentReply(message, replyTo)
                        return@onPhoto
                    }

                    if (message.mediaGroupId == null) {
                        sendMessage(message.chat, "Фото получено, обрабатываю...")
                    }

                    val photoBytes = bot.downloadFile(message.content)
                    val text = ocrService.recognizeTextWithPreprocessing(photoBytes)
                    
                    if (text.isBlank()) {
                        sendMessage(message.chat, "⚠️ Не удалось распознать текст на фото")
                        return@onPhoto
                    }

                    val forteTransaction = parser.parseTransaction(text)
                    if (forteTransaction == null) {
                        sendMessage(message.chat, "⚠️ Не удалось распознать данные транзакции\\. Проверьте формат фото\\."
                            .escapeMarkdownV2Common())
                        return@onPhoto
                    }

                    val detectedCurrency = parser.detectCurrency(forteTransaction.currencySymbol)
                    val sourceAccount = currencyAccounts[detectedCurrency]
                        ?: throw RuntimeException("No account configured for currency $detectedCurrency. Available: ${currencyAccounts.keys}")

                    val foreignAmount = forteTransaction.transactionAmount
                    val foreignCurrency = if (foreignAmount != null) defaultCurrency else null

                    val transactionRequest = TransactionRequest(
                        transactions = listOf(
                            TransactionSplit(
                                type = "withdrawal",
                                date = parser.convertToFireflyDate(forteTransaction.dateTime),
                                amount = forteTransaction.amount,
                                description = forteTransaction.description,
                                sourceName = sourceAccount,
                                destinationName = forteTransaction.description,
                                currencyCode = detectedCurrency,
                                foreignAmount = foreignAmount,
                                foreignCurrencyCode = foreignCurrency,
                                externalId = forteTransaction.transactionNumber,
                                notes = "Imported from Forte via Telegram Bot",
                                budgetName = Budget.MAIN.budgetName
                            )
                        )
                    )

                    val transactionResponse = fireflyClient.createTransaction(transactionRequest)
                    val journalId = transactionResponse.data.attributes.transactions.first().transactionJournalId

                    val attachmentRequest = AttachmentRequest(
                        filename = "forte_transaction_${forteTransaction.transactionNumber}.jpg",
                        attachableType = "TransactionJournal",
                        attachableId = journalId,
                        title = "Forte Transaction Photo",
                        notes = "Original transaction photo from Forte"
                    )

                    val attachmentResponse = fireflyClient.createAttachment(attachmentRequest)
                    val uploadUrl = attachmentResponse.data.attributes.uploadUrl
                    if (uploadUrl != null) {
                        fireflyClient.uploadAttachment(uploadUrl, photoBytes)
                    }

                    val foreignAmountLine = if (foreignAmount != null) {
                        "💵 В ${defaultCurrency}: ${foreignAmount}"
                    } else {
                        null
                    }

                    val successMessage = buildString {
                        appendLine("✅ Транзакция успешно сохранена в Firefly III")
                        appendLine()
                        appendLine("📝 Описание: ${forteTransaction.description}")
                        appendLine("💰 Сумма: ${forteTransaction.amount} ${detectedCurrency}")
                        if (foreignAmountLine != null) {
                            appendLine(foreignAmountLine)
                        }
                        appendLine("🏦 Счёт: ${sourceAccount}")
                        appendLine("📅 Дата: ${forteTransaction.dateTime}")
                        append("🔢 ID транзакции: ${transactionResponse.data.id}")
                    }

                    sendMessage(
                        message.chat,
                        successMessage,
                        replyMarkup = createBudgetKeyboard(transactionResponse.data.id, Budget.MAIN)
                    )

                } catch (e: Exception) {
                    logger.error("Error processing photo", e)
                    sendMessage(message.chat, "❌ Ошибка при обработке фото: ${e.message ?: "Неизвестная ошибка"}")
                }
            }

            onDocument { message ->
                try {
                    val replyTo = message.replyTo
                    if (replyTo != null) {
                        handleAttachmentReply(message, replyTo)
                        return@onDocument
                    }

                    sendMessage(message.chat, "⚠️ Чтобы прикрепить документ к транзакции, отправьте его как reply на сообщение с ID транзакции")
                } catch (e: Exception) {
                    logger.error("Error processing document", e)
                    sendMessage(message.chat, "❌ Ошибка при обработке документа: ${e.message ?: "Неизвестная ошибка"}")
                }
            }

            onLocation { message ->
                try {
                    val replyTo = message.replyTo
                    if (replyTo == null) {
                        sendMessage(message.chat, "⚠️ Чтобы добавить локацию к транзакции, отправьте её как reply на сообщение с ID транзакции")
                        return@onLocation
                    }

                    val replyContent = (replyTo as? dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<*>)?.content
                    val textContent = when (replyContent) {
                        is dev.inmo.tgbotapi.types.message.content.TextContent -> replyContent.text
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

                    val currentTransaction = fireflyClient.getTransaction(transactionId)
                    val currentSplit = currentTransaction.data.attributes.transactions.first()

                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    val changeLog = "[$timestamp] Локация добавлена: ${location.latitude}, ${location.longitude}"
                    val updatedNotes = if (currentSplit.notes.isNullOrBlank()) {
                        changeLog
                    } else {
                        "${currentSplit.notes}\n$changeLog"
                    }

                    val updatedSplit = TransactionSplit(
                        type = currentSplit.type,
                        date = currentSplit.date,
                        amount = currentSplit.amount,
                        description = currentSplit.description,
                        sourceName = currentSplit.sourceName,
                        destinationName = currentSplit.destinationName,
                        currencyCode = currentSplit.currencyCode ?: defaultCurrency,
                        foreignAmount = currentSplit.foreignAmount,
                        foreignCurrencyCode = currentSplit.foreignCurrencyCode,
                        externalId = currentSplit.externalId,
                        notes = updatedNotes,
                        tags = currentSplit.tags,
                        budgetId = currentSplit.budgetId,
                        budgetName = currentSplit.budgetName,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        zoomLevel = 15
                    )

                    val updateRequest = TransactionRequest(
                        transactions = listOf(updatedSplit)
                    )

                    fireflyClient.updateTransaction(transactionId, updateRequest)

                    if (replyContent is dev.inmo.tgbotapi.types.message.content.TextContent &&
                        replyTo is dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<*>) {
                        val originalMessage = replyContent.text
                        val updatedMessage = buildString {
                            append(originalMessage)
                            appendLine()
                            appendLine()
                            append("📍 Локация добавлена: ${location.latitude}, ${location.longitude}")
                        }

                        try {
                            @Suppress("UNCHECKED_CAST")
                            bot.edit(
                                replyTo as dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<TextContent>,
                                updatedMessage
                            )
                        } catch (e: Exception) {
                            logger.warn("Could not edit original message: ${e.message}")
                        }
                    }

                    sendMessage(
                        message.chat,
                        "✅ Локация успешно добавлена к транзакции #$transactionId\n📍 ${location.latitude}, ${location.longitude}"
                    )

                } catch (e: Exception) {
                    logger.error("Error processing location", e)
                    sendMessage(message.chat, "❌ Ошибка при добавлении локации: ${e.message ?: "Неизвестная ошибка"}")
                }
            }

            onContentMessage(
                initialFilter = { it.content is TextContent }
            ) { message ->
                try {
                    val replyTo = message.replyTo
                    if (replyTo != null) {
                        @Suppress("UNCHECKED_CAST")
                        handleAmountCorrection(
                            message as dev.inmo.tgbotapi.types.message.abstracts.CommonMessage<TextContent>,
                            replyTo
                        )
                    }
                } catch (e: Exception) {
                    logger.error("Error processing text message", e)
                    sendMessage(message.chat, "❌ Ошибка: ${e.message ?: "Неизвестная ошибка"}")
                }
            }

            onVisualGallery { gallery ->
                val messages = gallery.group
                val totalCount = messages.size
                var successCount = 0
                var failedCount = 0

                messages.forEachIndexed { index, msg ->
                    val currentNumber = index + 1
                    val progress = "$currentNumber/$totalCount"
                    
                    try {
                        val photoBytes = bot.downloadFile(msg.content)
                        val msgChat = msg.sourceMessage.chat

                        val text = ocrService.recognizeTextWithPreprocessing(photoBytes)
                        if (text.isBlank()) {
                            failedCount++
                            sendMessage(msgChat, "⚠️ [$progress] Не удалось распознать текст на фото")
                            return@forEachIndexed
                        }

                        val forteTransaction = parser.parseTransaction(text)
                        if (forteTransaction == null) {
                            failedCount++
                            sendMessage(msgChat, "⚠️ [$progress] Не удалось распознать данные транзакции")
                            return@forEachIndexed
                        }

                        val detectedCurrency = parser.detectCurrency(forteTransaction.currencySymbol)
                        val sourceAccount = currencyAccounts[detectedCurrency]
                            ?: throw RuntimeException("No account configured for currency $detectedCurrency")

                        val foreignAmount = forteTransaction.transactionAmount
                        val foreignCurrency = if (foreignAmount != null) defaultCurrency else null

                        val transactionRequest = TransactionRequest(
                            transactions = listOf(
                                TransactionSplit(
                                    type = "withdrawal",
                                    date = parser.convertToFireflyDate(forteTransaction.dateTime),
                                    amount = forteTransaction.amount,
                                    description = forteTransaction.description,
                                    sourceName = sourceAccount,
                                    destinationName = forteTransaction.description,
                                    currencyCode = detectedCurrency,
                                    foreignAmount = foreignAmount,
                                    foreignCurrencyCode = foreignCurrency,
                                    externalId = forteTransaction.transactionNumber,
                                    notes = "Imported from Forte via Telegram Bot",
                                    budgetName = Budget.MAIN.budgetName
                                )
                            )
                        )

                        val transactionResponse = fireflyClient.createTransaction(transactionRequest)
                        val journalId = transactionResponse.data.attributes.transactions.first().transactionJournalId

                        val attachmentRequest = AttachmentRequest(
                            filename = "forte_transaction_${forteTransaction.transactionNumber}.jpg",
                            attachableType = "TransactionJournal",
                            attachableId = journalId,
                            title = "Forte Transaction Photo",
                            notes = "Original transaction photo from Forte"
                        )

                        val attachmentResponse = fireflyClient.createAttachment(attachmentRequest)
                        val uploadUrl = attachmentResponse.data.attributes.uploadUrl
                        if (uploadUrl != null) {
                            fireflyClient.uploadAttachment(uploadUrl, photoBytes)
                        }

                        val foreignAmountLine = if (foreignAmount != null) {
                            "💵 В ${defaultCurrency}: ${foreignAmount}"
                        } else {
                            null
                        }

                        val successMessage = buildString {
                            appendLine("✅ [$progress] Транзакция сохранена")
                            appendLine("📝 ${forteTransaction.description}")
                            appendLine("💰 ${forteTransaction.amount} ${detectedCurrency}")
                            if (foreignAmountLine != null) {
                                appendLine(foreignAmountLine)
                            }
                            append("🔢 ID: ${transactionResponse.data.id}")
                        }

                        sendMessage(
                            msgChat,
                            successMessage,
                            replyMarkup = createBudgetKeyboard(transactionResponse.data.id, Budget.MAIN)
                        )
                        successCount++

                    } catch (e: Exception) {
                        logger.error("Error processing photo $progress from gallery", e)
                        failedCount++
                        sendMessage(msg.sourceMessage.chat, "❌ [$progress] Ошибка: ${e.message ?: "Неизвестная ошибка"}")
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

            onMessageDataCallbackQuery(
                initialFilter = { it.data.startsWith("budget:") }
            ) { query ->
                try {
                    val parts = query.data.split(":")
                    if (parts.size != 3) {
                        logger.error("Invalid callback data format: ${query.data}")
                        return@onMessageDataCallbackQuery
                    }

                    val transactionId = parts[1]
                    val currentBudgetName = parts[2]
                    val currentBudget = Budget.fromNameOrDefault(currentBudgetName)
                    val newBudget = currentBudget.getNext()

                    val transaction = fireflyClient.getTransaction(transactionId)
                    val currentSplit = transaction.data.attributes.transactions.first()

                    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    val changeLog = "[$timestamp] Бюджет изменен: ${currentBudget.budgetName} → ${newBudget.budgetName}"
                    val updatedNotes = if (currentSplit.notes.isNullOrBlank()) {
                        changeLog
                    } else {
                        "${currentSplit.notes}\n$changeLog"
                    }

                    val updatedSplit = TransactionSplit(
                        type = currentSplit.type,
                        date = currentSplit.date,
                        amount = currentSplit.amount,
                        description = currentSplit.description,
                        sourceName = currentSplit.sourceName,
                        destinationName = currentSplit.destinationName,
                        currencyCode = currentSplit.currencyCode ?: defaultCurrency,
                        foreignAmount = currentSplit.foreignAmount,
                        foreignCurrencyCode = currentSplit.foreignCurrencyCode,
                        externalId = currentSplit.externalId,
                        notes = updatedNotes,
                        tags = currentSplit.tags,
                        budgetName = newBudget.budgetName,
                        latitude = currentSplit.latitude,
                        longitude = currentSplit.longitude,
                        zoomLevel = currentSplit.zoomLevel
                    )

                    val updateRequest = TransactionRequest(
                        transactions = listOf(updatedSplit)
                    )

                    fireflyClient.updateTransaction(transactionId, updateRequest)

                    val queryMessage = query.message
                    if (queryMessage.content is TextContent) {
                        val originalMessage = (queryMessage.content as TextContent).text
                        val updatedMessage = buildString {
                            append(originalMessage)
                            appendLine()
                            appendLine()
                            append("📊 Бюджет изменен: ${newBudget.emoji} ${newBudget.budgetName}")
                        }

                        @Suppress("UNCHECKED_CAST")
                        bot.edit(
                            queryMessage as dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<TextContent>,
                            updatedMessage,
                            replyMarkup = createBudgetKeyboard(transactionId, newBudget)
                        )
                    }

                } catch (e: Exception) {
                    logger.error("Error handling budget callback", e)
                }
            }
        }.join()
    }
}
