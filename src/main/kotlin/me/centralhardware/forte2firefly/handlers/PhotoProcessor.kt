package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.chat.Chat
import me.centralhardware.forte2firefly.Config
import me.centralhardware.forte2firefly.model.Budget
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.model.TransactionSplit
import me.centralhardware.forte2firefly.service.FireflyApiClient
import me.centralhardware.forte2firefly.service.OCRService
import me.centralhardware.forte2firefly.service.TransactionParser

suspend fun processPhotoTransaction(
    photoBytes: ByteArray,
    chatId: Chat,
    parser: TransactionParser,
    ocrService: OCRService,
    bot: TelegramBot,
    progressPrefix: String = ""
): String? {
    val text = ocrService.recognizeText(photoBytes)

    if (text.isBlank()) {
        bot.sendMessage(chatId, "$progressPrefix⚠️ Не удалось распознать текст на фото", linkPreviewOptions = LinkPreviewOptions.Disabled)
        return null
    }

    val forteTransaction = parser.parseTransaction(text)
    if (forteTransaction == null) {
        bot.sendMessage(chatId, "$progressPrefix⚠️ Не удалось распознать данные транзакции", linkPreviewOptions = LinkPreviewOptions.Disabled)
        return null
    }

    val detectedCurrency = parser.detectCurrency(forteTransaction.currencySymbol)
    val sourceAccount = Config.currencyAccounts[detectedCurrency]
        ?: throw RuntimeException("No account configured for currency $detectedCurrency")

    val foreignAmount = forteTransaction.transactionAmount
    val foreignCurrency = if (foreignAmount != null) Config.defaultCurrency else null

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
        "💵 В ${Config.defaultCurrency}: ${foreignAmount}"
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
        appendLine("📝 ${forteTransaction.description}")
        appendLine("💰 ${forteTransaction.amount} ${detectedCurrency}")
        if (foreignAmountLine != null) {
            appendLine(foreignAmountLine)
        }
        if (progressPrefix.isEmpty()) {
            appendLine("🏦 Счёт: ${sourceAccount}")
            appendLine("📅 Дата: ${forteTransaction.dateTime}")
        }
        append("🔢 ID: ${transactionResponse.data.id}")
    }

    bot.sendMessage(chatId, successMessage, linkPreviewOptions = LinkPreviewOptions.Disabled, replyMarkup = createBudgetKeyboard(transactionResponse.data.id, Budget.MAIN))

    return transactionResponse.data.id
}
