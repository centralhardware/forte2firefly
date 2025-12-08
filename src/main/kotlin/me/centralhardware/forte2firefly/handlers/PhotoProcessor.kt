package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.chat.Chat
import me.centralhardware.forte2firefly.Config
import me.centralhardware.forte2firefly.model.Budget
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.model.TransactionSplit
import me.centralhardware.forte2firefly.service.FireflyApiClient
import me.centralhardware.forte2firefly.service.OCRService
import me.centralhardware.forte2firefly.service.TransactionParser

suspend fun BehaviourContext.processPhotoTransaction(
    photoBytes: ByteArray,
    chatId: Chat,
    progressPrefix: String = ""
): String? {
    val forteTransaction = OCRService.extractAllFields(photoBytes)
    
    if (forteTransaction == null) {
        bot.sendMessage(chatId, "$progressPrefix⚠️ Не удалось распознать данные транзакции", linkPreviewOptions = LinkPreviewOptions.Disabled)
        return null
    }
    
    val transactionWithMcc = forteTransaction

    val detectedCurrency = OCRService.detectCurrency(transactionWithMcc.currencySymbol)
    val sourceAccount = Config.currencyAccounts[detectedCurrency]
        ?: throw RuntimeException("No account configured for currency $detectedCurrency")

    val foreignAmount = transactionWithMcc.transactionAmount
    val foreignCurrency = if (foreignAmount != null) Config.defaultCurrency else null

    val tags = buildList {
        if (transactionWithMcc.mccCode != null) {
            add("mcc:${transactionWithMcc.mccCode}")
        }
    }.takeIf { it.isNotEmpty() }

    val transactionRequest = TransactionRequest(
        transactions = listOf(
            TransactionSplit(
                type = "withdrawal",
                date = TransactionParser.convertToFireflyDate(transactionWithMcc.dateTime),
                amount = transactionWithMcc.amount,
                description = transactionWithMcc.description,
                sourceName = sourceAccount,
                destinationName = transactionWithMcc.description,
                currencyCode = detectedCurrency,
                foreignAmount = foreignAmount,
                foreignCurrencyCode = foreignCurrency,
                externalId = transactionWithMcc.transactionNumber,
                notes = "Imported from Forte via Telegram Bot",
                budgetName = Budget.MAIN.budgetName,
                tags = tags
            )
        )
    )

    val transactionResponse = FireflyApiClient.createTransaction(transactionRequest)
    val journalId = transactionResponse.data.attributes.transactions.first().transactionJournalId
        ?: throw RuntimeException("Transaction journal ID is missing")

    FireflyApiClient.createAndUploadAttachment(
        transactionJournalId = journalId,
        filename = "forte_transaction_${forteTransaction.transactionNumber}.jpg",
        title = "Forte Transaction Photo",
        fileBytes = photoBytes,
        notes = "Original transaction photo from Forte"
    )

    val foreignAmountLine = if (foreignAmount != null) {
        "💵 В ${Config.defaultCurrency}: $foreignAmount"
    } else {
        null
    }

    val successMessage = buildString {
        if (progressPrefix.isNotEmpty()) {
            appendLine("$progressPrefix✅ Транзакция сохранена")
        } else {
            appendLine("✅ Транзакция успешно сохранена в Firefly III")
            appendLine()
        }
        appendLine("📝 ${transactionWithMcc.description}")
        appendLine("💰 ${transactionWithMcc.amount} $detectedCurrency")
        if (foreignAmountLine != null) {
            appendLine(foreignAmountLine)
        }
        if (progressPrefix.isEmpty()) {
            appendLine("🏦 Счёт: $sourceAccount")
            appendLine("📅 Дата: ${transactionWithMcc.dateTime.toLocalDateTime()}")
        }
        append("🔢 ID: ${transactionResponse.data.id}")
    }

    bot.sendMessage(chatId, successMessage, linkPreviewOptions = LinkPreviewOptions.Disabled, replyMarkup = createBudgetKeyboard(transactionResponse.data.id, Budget.MAIN))

    return transactionResponse.data.id
}
