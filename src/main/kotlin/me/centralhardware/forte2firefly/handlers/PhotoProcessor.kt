package me.centralhardware.forte2firefly.handlers

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.chat.Chat
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.centralhardware.forte2firefly.Config
import me.centralhardware.forte2firefly.model.Budget
import me.centralhardware.forte2firefly.model.ForteTransaction
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.model.TransactionSplit
import me.centralhardware.forte2firefly.service.CurrencyService
import me.centralhardware.forte2firefly.service.FireflyApiClient
import me.centralhardware.forte2firefly.service.OCRService
import me.centralhardware.forte2firefly.service.TransactionParser

fun ForteTransaction.toTransactionSplit(
    detectedCurrency: String,
    sourceAccount: String
): TransactionSplit {
    val foreignCurrency = if (transactionAmount != null) Config.defaultCurrency else null

    val tags = mccCode?.let { listOf("mcc:$it") }

    return TransactionSplit(
        type = "withdrawal",
        date = TransactionParser.convertToFireflyDate(dateTime),
        amount = amount,
        description = description,
        sourceName = sourceAccount,
        destinationName = description,
        currencyCode = detectedCurrency,
        foreignAmount = transactionAmount,
        foreignCurrencyCode = foreignCurrency,
        externalId = transactionNumber,
        notes = null,
        budgetName = Budget.MAIN.budgetName,
        tags = tags
    )
}

suspend fun BehaviourContext.processPhotoTransaction(
    photoBytes: ByteArray,
    chatId: Chat,
    progressPrefix: String = ""
): String? {
    val transaction = OCRService.extractAllFields(photoBytes)

    if (transaction == null) {
        bot.sendMessage(chatId, "$progressPrefix⚠️ Не удалось распознать данные транзакции", linkPreviewOptions = LinkPreviewOptions.Disabled)
        return null
    }

    val detectedCurrency = CurrencyService.detectCurrency(transaction.currencySymbol)
    val sourceAccount = Config.currencyAccounts[detectedCurrency]
        ?: throw RuntimeException("No account configured for currency $detectedCurrency")

    val transactionRequest = TransactionRequest(
        transactions = listOf(transaction.toTransactionSplit(detectedCurrency, sourceAccount))
    )

    val transactionResponse = FireflyApiClient.createTransaction(transactionRequest)
    val journalId = transactionResponse.data.attributes.transactions.first().transactionJournalId
        ?: throw RuntimeException("Transaction journal ID is missing")

    FireflyApiClient.createAndUploadAttachment(
        transactionJournalId = journalId,
        filename = "forte_transaction_${transaction.transactionNumber}.jpg",
        title = "Forte Transaction Photo",
        fileBytes = photoBytes,
        notes = null
    )

    val successMessage = buildString {
        if (progressPrefix.isNotEmpty()) {
            appendLine("$progressPrefix✅ Транзакция сохранена")
        } else {
            appendLine("✅ Транзакция успешно сохранена в Firefly III")
            appendLine()
        }
        appendLine("📝 ${transaction.description}")
        appendLine("💰 ${transaction.amount} $detectedCurrency")
        transaction.transactionAmount?.let { appendLine("💵 В ${Config.defaultCurrency}: $it") }
        if (progressPrefix.isEmpty()) {
            appendLine("🏦 Счёт: $sourceAccount")
            appendLine("📅 Дата: ${transaction.dateTime.toLocalDateTime()}")
        }
        append("🔢 ID транзакции: ${transactionResponse.data.id}, Journal: $journalId")
    }

    val sentMessage = bot.sendMessage(chatId, successMessage, linkPreviewOptions = LinkPreviewOptions.Disabled, replyMarkup = createBudgetKeyboard(transactionResponse.data.id, Budget.MAIN))

    updateBudgetAfterRules(transactionResponse.data.id, sentMessage)

    return transactionResponse.data.id
}
