package me.centralhardware.forte2firefly.handlers

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info
import dev.inmo.tgbotapi.extensions.api.edit.edit
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

        val transactionIdRegex = """(?:ID транзакции|ID):\s*(\d+)""".toRegex()
        val matchResult = transactionIdRegex.find(textContent)

        if (matchResult == null) {
            sendMessage(message.chat, "⚠️ Не удалось найти ID транзакции в сообщении. Используйте reply на сообщение с ID транзакции.", linkPreviewOptions = LinkPreviewOptions.Disabled)
            return
        }

        val transactionId = matchResult.groupValues[1]
        val fileBytes = downloadFile(message.content)

        // Try to parse as transaction if it's a photo
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

        val transaction = FireflyApiClient.getTransaction(transactionId)
        val journalIds = transaction.data.attributes.transactions.mapNotNull { it.transactionJournalId }

        if (journalIds.isEmpty()) {
            throw RuntimeException("No transaction journal IDs found")
        }

        val messageText = when (val content = message.content) {
            is PhotoContent -> content.text
            is DocumentContent -> content.text
            else -> null
        }?.trim()

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        val timestamp = LocalDateTime.now().format(formatter)

        val baseFilename: String
        val baseTitle: String
        when (val content = message.content) {
            is PhotoContent -> {
                if (!messageText.isNullOrBlank()) {
                    baseFilename = messageText
                    baseTitle = messageText
                } else {
                    baseFilename = "photo_$timestamp"
                    baseTitle = "Photo $timestamp"
                }
            }
            is DocumentContent -> {
                val originalName = content.media.fileName

                if (originalName != null) {
                    baseFilename = originalName.substringBeforeLast(".")
                    baseTitle = messageText?.takeIf { it.isNotBlank() } ?: originalName
                } else {
                    if (!messageText.isNullOrBlank()) {
                        baseFilename = messageText
                        baseTitle = messageText
                    } else {
                        baseFilename = "document_$timestamp"
                        baseTitle = "Document $timestamp"
                    }
                }
            }
            else -> {
                baseFilename = if (!messageText.isNullOrBlank()) {
                    messageText
                } else {
                    "attachment_$timestamp"
                }
                baseTitle = messageText ?: "Attachment $timestamp"
            }
        }

        // Attach to each split in the transaction
        journalIds.forEachIndexed { index, journalId ->
            val suffix = if (journalIds.size > 1) "_split${index + 1}" else ""
            val extension = when (message.content) {
                is PhotoContent -> ".jpg"
                is DocumentContent -> {
                    val originalName = (message.content as DocumentContent).media.fileName
                    if (originalName != null && originalName.contains(".")) {
                        ".${originalName.substringAfterLast(".")}"
                    } else {
                        ""
                    }
                }
                else -> ""
            }
            val filename = "$baseFilename$suffix$extension"
            val title = if (journalIds.size > 1) "$baseTitle (Split ${index + 1})" else baseTitle

            FireflyApiClient.createAndUploadAttachment(
                transactionJournalId = journalId,
                filename = filename,
                title = title,
                fileBytes = fileBytes,
                notes = "Added via reply in Telegram Bot"
            )
        }

        val attachmentCount = journalIds.size
        val successText = if (attachmentCount > 1) {
            "✅ Файл успешно прикреплен к $attachmentCount splits транзакции #$transactionId"
        } else {
            "✅ Файл успешно прикреплен к транзакции #$transactionId"
        }
        sendMessage(message.chat, successText, linkPreviewOptions = LinkPreviewOptions.Disabled)

    } catch (e: Exception) {
        KSLog.error("Error processing attachment reply", e)
        sendMessage(message.chat, "❌ Ошибка при прикреплении файла: ${e.message ?: "Неизвестная ошибка"}", linkPreviewOptions = LinkPreviewOptions.Disabled)
    }
}

suspend fun BehaviourContext.addTransactionToSplit(
    transactionId: String,
    newTransactionBytes: ByteArray,
    chatId: dev.inmo.tgbotapi.types.chat.Chat
) {
    try {
        // Get existing transaction
        val existingTransaction = FireflyApiClient.getTransaction(transactionId)
        val existingSplits = existingTransaction.data.attributes.transactions

        // Parse new transaction from photo
        val forteTransaction = OCRService.extractAllFields(newTransactionBytes)
            ?: throw RuntimeException("Failed to parse transaction from photo")

        val detectedCurrency = CurrencyService.detectCurrency(forteTransaction.currencySymbol)
        val sourceAccount = Config.currencyAccounts[detectedCurrency]
            ?: throw RuntimeException("No account configured for currency $detectedCurrency")

        // Create new split from parsed transaction
        val newSplit = forteTransaction.toTransactionSplit(detectedCurrency, sourceAccount)

        // Combine existing and new splits
        val allSplits = existingSplits + newSplit

        // Generate group title
        val descriptions = allSplits.map { it.description }
        val groupTitle = if (descriptions.toSet().size == 1) {
            descriptions.first()
        } else {
            descriptions.mapIndexed { index, desc -> "${index + 1}. $desc" }.joinToString(" | ")
        }

        // Update transaction with new split
        val updateRequest = TransactionRequest(
            groupTitle = groupTitle,
            transactions = allSplits
        )

        val updatedTransaction = FireflyApiClient.updateTransaction(transactionId, updateRequest)

        // Get the journal ID of the newly added split
        val updatedSplits = updatedTransaction.data.attributes.transactions
        val newJournalId = updatedSplits.last().transactionJournalId
            ?: throw RuntimeException("Transaction journal ID is missing for new split")

        // Attach photo to the new split
        FireflyApiClient.createAndUploadAttachment(
            transactionJournalId = newJournalId,
            filename = "forte_transaction_${forteTransaction.transactionNumber}_split${updatedSplits.size}.jpg",
            title = "Split ${updatedSplits.size} - Forte Transaction Photo",
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
            append("🔢 ID: $transactionId")
        }

        val sentMessage = bot.sendMessage(
            chatId,
            successMessage,
            linkPreviewOptions = LinkPreviewOptions.Disabled,
            replyMarkup = createBudgetKeyboard(transactionId, Budget.MAIN)
        )

        // Update budget keyboard after Firefly rules are applied
        updateBudgetAfterRules(transactionId, sentMessage)

    } catch (e: Exception) {
        KSLog.error("Error adding transaction to split", e)
        sendMessage(chatId, "❌ Ошибка при добавлении транзакции в split: ${e.message ?: "Неизвестная ошибка"}", linkPreviewOptions = LinkPreviewOptions.Disabled)
    }
}

private fun BehaviourContext.updateBudgetAfterRules(
    transactionId: String,
    message: ContentMessage<TextContent>
) {
    launch {
        try {
            // Wait for Firefly rules to be applied
            delay(2000)

            // Get actual budget from Firefly
            val transaction = FireflyApiClient.getTransaction(transactionId)
            val actualBudgetName = transaction.data.attributes.transactions.first().budgetName
            val actualBudget = Budget.fromNameOrDefault(actualBudgetName)

            // Update message with actual budget button
            edit(
                message,
                message.content.text,
                replyMarkup = createBudgetKeyboard(transactionId, actualBudget)
            )
        } catch (e: Exception) {
            KSLog.error("Error updating budget keyboard after rules", e)
        }
    }
}
