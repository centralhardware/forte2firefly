package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.types.chat.Chat
import me.centralhardware.forte2firefly.model.AttachmentRequest
import me.centralhardware.forte2firefly.model.Budget
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.model.TransactionSplit
import me.centralhardware.forte2firefly.service.FireflyApiClient
import me.centralhardware.forte2firefly.service.OCRService
import me.centralhardware.forte2firefly.service.TransactionParser
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PhotoProcessor")

suspend fun processPhotoTransaction(
    photoBytes: ByteArray,
    chatId: Chat,
    fireflyClient: FireflyApiClient,
    parser: TransactionParser,
    ocrService: OCRService,
    defaultCurrency: String,
    currencyAccounts: Map<String, String>,
    bot: TelegramBot,
    progressPrefix: String = ""
): String? {
    val text = ocrService.recognizeTextWithPreprocessing(photoBytes)

    if (text.isBlank()) {
        bot.sendMessage(chatId, "$progressPrefix⚠️ Не удалось распознать текст на фото")
        return null
    }

    val forteTransaction = parser.parseTransaction(text)
    if (forteTransaction == null) {
        bot.sendMessage(chatId, "$progressPrefix⚠️ Не удалось распознать данные транзакции")
        return null
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
        ?: throw RuntimeException("Transaction journal ID is missing")

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

    bot.sendMessage(chatId, successMessage, replyMarkup = createBudgetKeyboard(transactionResponse.data.id, Budget.MAIN))

    return transactionResponse.data.id
}
