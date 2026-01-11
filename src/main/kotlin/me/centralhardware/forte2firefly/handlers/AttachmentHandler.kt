package me.centralhardware.forte2firefly.handlers

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.message.abstracts.CommonMessage
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.abstracts.Message
import dev.inmo.tgbotapi.types.message.content.DocumentContent
import dev.inmo.tgbotapi.types.message.content.MediaContent
import dev.inmo.tgbotapi.types.message.content.PhotoContent
import dev.inmo.tgbotapi.types.message.content.TextContent
import me.centralhardware.forte2firefly.Config
import me.centralhardware.forte2firefly.model.Budget
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.service.CurrencyService
import me.centralhardware.forte2firefly.service.FireflyApiClient
import me.centralhardware.forte2firefly.service.OCRService
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

suspend fun <T : MediaContent> BehaviourContext.handleAttachmentReply(
    message: CommonMessage<T>,
    replyTo: Message
) {
    try {
        val textContent = when (val replyContent = (replyTo as? ContentMessage<*>)?.content) {
            is TextContent -> replyContent.text
            else -> {
                sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении", linkPreviewOptions = LinkPreviewOptions.Disabled)
                return
            }
        }

        val idRegex = """ID транзакции:\s*(\d+),\s*Journal:\s*(\d+)""".toRegex()
        val matchResult = idRegex.find(textContent)
        
        if (matchResult == null) {
            sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении. Используйте reply на сообщение с ID транзакции.", linkPreviewOptions = LinkPreviewOptions.Disabled)
            return
        }
        
        val transactionId = matchResult.groupValues[1]
        val journalId = matchResult.groupValues[2]
        val fileBytes = downloadFile(message.content)

        if (message.content is PhotoContent) {
            val forteTransaction = OCRService.extractAllFields(fileBytes)

            if (forteTransaction != null) {
                KSLog.info("Photo recognized as transaction, adding to split")
                sendMessage(message.chat, "Добавляю новую транзакцию к split #$transactionId...", linkPreviewOptions = LinkPreviewOptions.Disabled)

                addTransactionToSplit(
                    transactionId = transactionId,
                    newTransactionBytes = fileBytes,
                    chatId = message.chat
                )
                return
            }
        }

        sendMessage(message.chat, "Прикрепляю файл к транзакции #$transactionId...", linkPreviewOptions = LinkPreviewOptions.Disabled)

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
                    if (!messageText.isNullOrBlank()) {
                        filename = messageText
                        title = messageText
                    } else {
                        filename = "document_$timestamp"
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

suspend fun BehaviourContext.addTransactionToSplit(
    transactionId: String,
    newTransactionBytes: ByteArray,
    chatId: Chat
) {
    try {
        val existingTransaction = FireflyApiClient.getTransaction(transactionId)
        val existingSplits = existingTransaction.data.attributes.transactions

        val forteTransaction = OCRService.extractAllFields(newTransactionBytes)
            ?: throw RuntimeException("Failed to parse transaction from photo")

        val detectedCurrency = CurrencyService.detectCurrency(forteTransaction.currencySymbol)
        val sourceAccount = Config.currencyAccounts[detectedCurrency]
            ?: throw RuntimeException("No account configured for currency $detectedCurrency")

        val splitResult = forteTransaction.toTransactionSplit(detectedCurrency, sourceAccount)

        val allSplits = existingSplits + splitResult.split

        val descriptions = allSplits.map { it.description }
        val groupTitle = if (descriptions.toSet().size == 1) {
            descriptions.first()
        } else {
            descriptions.mapIndexed { index, desc -> "${index + 1}. $desc" }.joinToString(" | ")
        }

        val updateRequest = TransactionRequest(
            groupTitle = groupTitle,
            transactions = allSplits
        )

        val updatedTransaction = FireflyApiClient.updateTransaction(transactionId, updateRequest)

        val updatedSplits = updatedTransaction.data.attributes.transactions
        val newSplitEntry = updatedSplits.find { it.externalId == forteTransaction.transactionNumber }
            ?: throw RuntimeException("Could not find newly added split with transaction number ${forteTransaction.transactionNumber}")

        val newJournalId = newSplitEntry.transactionJournalId
            ?: throw RuntimeException("Transaction journal ID is missing for new split")

        val splitIndex = updatedSplits.indexOf(newSplitEntry) + 1
        FireflyApiClient.createAndUploadAttachment(
            transactionJournalId = newJournalId,
            filename = "forte_transaction_${forteTransaction.transactionNumber}_split${splitIndex}.jpg",
            title = "Split $splitIndex - Forte Transaction Photo",
            fileBytes = newTransactionBytes,
            notes = null
        )

        val successMessage = buildString {
            appendLine("✅ Split успешно добавлен в транзакцию #$transactionId")
            appendLine()
            appendLine("📝 ${forteTransaction.description}")
            appendLine("💰 ${forteTransaction.amount} $detectedCurrency")
            forteTransaction.transactionAmount?.let { appendLine("💵 В ${Config.defaultCurrency}: $it") }
            appendLine("🏦 Счёт: $sourceAccount")
            appendLine("📅 Дата: ${forteTransaction.dateTime.toLocalDateTime()}")
            append("🔢 ID транзакции: $transactionId, Journal: $newJournalId")
        }

        val sentMessage = bot.sendMessage(
            chatId,
            successMessage,
            linkPreviewOptions = LinkPreviewOptions.Disabled,
            replyMarkup = createBudgetKeyboard(transactionId, Budget.MAIN)
        )

        updateBudgetAfterRules(transactionId, sentMessage)

    } catch (e: Exception) {
        KSLog.error("Error adding transaction to split", e)
        sendMessage(chatId, "❌ Ошибка при добавлении транзакции в split: ${e.message ?: "Неизвестная ошибка"}", linkPreviewOptions = LinkPreviewOptions.Disabled)
    }
}
