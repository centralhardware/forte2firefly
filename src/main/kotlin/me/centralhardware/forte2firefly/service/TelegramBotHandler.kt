package me.centralhardware.forte2firefly.service

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onPhoto
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onVisualGallery
import dev.inmo.tgbotapi.utils.extensions.escapeMarkdownV2Common
import me.centralhardware.forte2firefly.model.AttachmentRequest
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.model.TransactionSplit
import org.slf4j.LoggerFactory

class TelegramBotHandler(
    private val bot: TelegramBot,
    private val fireflyClient: FireflyApiClient,
    private val parser: TransactionParser,
    private val ocrService: OCRService,
    private val defaultCurrency: String = "MYR",
    private val currencyAccounts: Map<String, String>
) {
    private val logger = LoggerFactory.getLogger(TelegramBotHandler::class.java)

    private suspend fun handleAttachmentReply(
        message: dev.inmo.tgbotapi.types.message.abstracts.CommonMessage<dev.inmo.tgbotapi.types.message.content.PhotoContent>,
        replyTo: dev.inmo.tgbotapi.types.message.abstracts.Message
    ) {
        try {
            logger.info("Processing attachment reply")

            // Пытаемся извлечь ID транзакции из текста сообщения, на которое сделан reply
            val replyText = (replyTo as? dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<*>)?.content
            val textContent = when (replyText) {
                is dev.inmo.tgbotapi.types.message.content.TextContent -> replyText.text
                else -> {
                    logger.warn("Reply message does not contain text")
                    bot.sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении")
                    return
                }
            }

            logger.info("Reply message text: $textContent")

            // Извлекаем ID транзакции из текста (ищем строку вида "ID транзакции: 123" или "ID: 123")
            val transactionIdRegex = """(?:ID транзакции|ID):\s*(\d+)""".toRegex()
            val matchResult = transactionIdRegex.find(textContent)
            
            if (matchResult == null) {
                logger.warn("Transaction ID not found in reply message")
                bot.sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении. Используйте reply на сообщение с ID транзакции.")
                return
            }

            val transactionId = matchResult.groupValues[1]
            logger.info("Extracted transaction ID: $transactionId")

            bot.sendMessage(message.chat, "Прикрепляю фото к транзакции #$transactionId...")

            // Получаем информацию о транзакции
            val transaction = fireflyClient.getTransaction(transactionId)
            val journalId = transaction.data.attributes.transactions.first().transactionJournalId

            logger.info("Found transaction journal ID: $journalId")

            // Скачиваем фото
            val photoBytes = bot.downloadFile(message.content)
            logger.info("Photo downloaded, size: ${photoBytes.size} bytes")

            // Создаем attachment
            val attachmentRequest = AttachmentRequest(
                filename = "attachment_${System.currentTimeMillis()}.jpg",
                attachableType = "TransactionJournal",
                attachableId = journalId,
                title = "Additional Photo",
                notes = "Added via reply in Telegram Bot"
            )

            val attachmentResponse = fireflyClient.createAttachment(attachmentRequest)
            logger.info("Attachment created with ID: ${attachmentResponse.data.id}")

            // Загружаем фото
            val uploadUrl = attachmentResponse.data.attributes.uploadUrl
            if (uploadUrl != null) {
                fireflyClient.uploadAttachment(uploadUrl, photoBytes)
                logger.info("Photo uploaded successfully")
            }

            bot.sendMessage(message.chat, "✅ Фото успешно прикреплено к транзакции #$transactionId")

        } catch (e: Exception) {
            logger.error("Error processing attachment reply", e)
            bot.sendMessage(message.chat, "❌ Ошибка при прикреплении фото: ${e.message ?: "Неизвестная ошибка"}")
        }
    }

    suspend fun start() {
        val botInfo = bot.getMe()
        logger.info("Bot started: @${botInfo.username}")

        bot.buildBehaviourWithLongPolling {
            onPhoto { message ->
                try {
                    val mediaGroupId = message.mediaGroupId?.string
                    logger.info("Received photo from user ${message.chat.id}, mediaGroupId: $mediaGroupId")

                    // Проверяем, является ли это reply на предыдущее сообщение с ID транзакции
                    val replyTo = message.replyTo
                    if (replyTo != null) {
                        logger.info("Photo is a reply to message: ${replyTo.messageId}")
                        handleAttachmentReply(message, replyTo)
                        return@onPhoto
                    }

                    // Отправляем подтверждение получения только для первого фото в группе или одиночного фото
                    // (чтобы не спамить при media group)
                    if (mediaGroupId == null) {
                        sendMessage(
                            message.chat,
                            "Фото получено, обрабатываю..."
                        )
                    }

                    // Получаем самое большое фото из группы
                    // В tgbotapi content имеет тип PhotoContent с полем mediaGroupId
                    val photo = message.content

                    // Скачиваем фото напрямую как ByteArray
                    val photoBytes = bot.downloadFile(photo)

                    logger.info("Photo downloaded, size: ${photoBytes.size} bytes")

                    // Распознаем текст с предобработкой для улучшения качества
                    val text = ocrService.recognizeTextWithPreprocessing(photoBytes)
                    if (text.isBlank()) {
                        sendMessage(message.chat, "⚠️ Не удалось распознать текст на фото")
                        return@onPhoto
                    }

                    logger.info("OCR result: $text")

                    // Парсим транзакцию
                    val forteTransaction = parser.parseTransaction(text)
                    if (forteTransaction == null) {
                        sendMessage(
                            message.chat,
                            "⚠️ Не удалось распознать данные транзакции\\. Проверьте формат фото\\."
                                .escapeMarkdownV2Common()
                        )
                        return@onPhoto
                    }

                    // Определяем валюту из транзакции
                    val detectedCurrency = parser.detectCurrency(forteTransaction.currencySymbol)

                    // Получаем source account для этой валюты
                    val sourceAccount = currencyAccounts[detectedCurrency]
                        ?: throw RuntimeException("No account configured for currency $detectedCurrency. Available: ${currencyAccounts.keys}")

                    // Foreign currency только если есть transaction amount
                    val foreignAmount = forteTransaction.transactionAmount
                    val foreignCurrency = if (foreignAmount != null) defaultCurrency else null

                    logger.info("Creating transaction: currency=$detectedCurrency, amount=${forteTransaction.amount}, foreign=${foreignCurrency ?: "none"} ${foreignAmount ?: ""}, source=$sourceAccount, destination=${forteTransaction.description}")

                    // Создаем транзакцию в Firefly
                    // Основная валюта - всегда detectedCurrency (USD/EUR/KZT)
                    // Foreign currency - MYR (только если есть transaction amount)
                    // Source - счет валюты (ACCOUNT_USD/EUR/KZT)
                    // Destination - имя мерчанта (из description)
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
                                notes = "Imported from Forte via Telegram Bot"
                            )
                        )
                    )

                    val transactionResponse = fireflyClient.createTransaction(transactionRequest)
                    logger.info("Transaction created with ID: ${transactionResponse.data.id}")

                    val journalId = transactionResponse.data.attributes.transactions.first().transactionJournalId

                    // Создаем attachment
                    val attachmentRequest = AttachmentRequest(
                        filename = "forte_transaction_${forteTransaction.transactionNumber}.jpg",
                        attachableType = "TransactionJournal",
                        attachableId = journalId,
                        title = "Forte Transaction Photo",
                        notes = "Original transaction photo from Forte"
                    )

                    val attachmentResponse = fireflyClient.createAttachment(attachmentRequest)
                    logger.info("Attachment created with ID: ${attachmentResponse.data.id}")

                    // Загружаем фото
                    val uploadUrl = attachmentResponse.data.attributes.uploadUrl
                    if (uploadUrl != null) {
                        fireflyClient.uploadAttachment(uploadUrl, photoBytes)
                        logger.info("Photo uploaded successfully")
                    }

                    // Отправляем подтверждение
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

                    sendMessage(message.chat, successMessage)
                    logger.info("Transaction processing completed successfully")

                } catch (e: Exception) {
                    logger.error("Error processing photo", e)
                    sendMessage(
                        message.chat,
                        "❌ Ошибка при обработке фото: ${e.message ?: "Неизвестная ошибка"}"
                    )
                }
            }

            onVisualGallery { gallery ->
                logger.info("Received visual gallery")
                val messages = gallery.group
                logger.info("Gallery has ${messages.size} messages")

                messages.forEach { msg ->
                    try {
                        logger.info("Processing message from gallery")
                        val photoBytes = bot.downloadFile(msg.content)
                        logger.info("Downloaded photo, size: ${photoBytes.size} bytes")
                        val msgChat = msg.sourceMessage.chat

                        val text = ocrService.recognizeTextWithPreprocessing(photoBytes)
                        if (text.isBlank()) {
                            logger.warn("Empty OCR result")
                            return@forEach
                        }

                        val forteTransaction = parser.parseTransaction(text) ?: run {
                            logger.warn("Could not parse transaction")
                            return@forEach
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
                                    notes = "Imported from Forte via Telegram Bot"
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
                            appendLine("✅ Транзакция сохранена")
                            appendLine("📝 ${forteTransaction.description}")
                            appendLine("💰 ${forteTransaction.amount} ${detectedCurrency}")
                            if (foreignAmountLine != null) {
                                appendLine(foreignAmountLine)
                            }
                            append("🔢 ID: ${transactionResponse.data.id}")
                        }

                        sendMessage(msgChat, successMessage)
                        logger.info("Transaction from gallery processed successfully")

                    } catch (e: Exception) {
                        logger.error("Error processing photo from gallery", e)
                        sendMessage(msg.sourceMessage.chat, "❌ Ошибка: ${e.message ?: "Неизвестная ошибка"}")
                    }
                }
            }
        }.join()
    }
}
