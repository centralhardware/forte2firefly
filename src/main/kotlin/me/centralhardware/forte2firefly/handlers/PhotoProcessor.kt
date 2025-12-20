package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.chat.Chat
import me.centralhardware.forte2firefly.Config
import me.centralhardware.forte2firefly.model.Budget
import me.centralhardware.forte2firefly.model.ForteTransaction
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.model.TransactionSplit
import me.centralhardware.forte2firefly.service.CurrencyService
import me.centralhardware.forte2firefly.service.FireflyApiClient
import me.centralhardware.forte2firefly.service.OCRService
import me.centralhardware.forte2firefly.service.TransactionParser

suspend fun BehaviourContext.processSplitTransaction(
    photoBytes: List<ByteArray>,
    chatId: Chat
) {
    val transactions = photoBytes.mapIndexed { index, bytes ->
        val transaction = OCRService.extractAllFields(bytes)
        if (transaction == null) {
            bot.sendMessage(
                chatId,
                "⚠️ Не удалось распознать данные транзакции на фотографии ${index + 1}",
                linkPreviewOptions = LinkPreviewOptions.Disabled
            )
        }
        transaction
    }

    if (transactions.any { it == null }) {
        return
    }

    val validTransactions = transactions.filterNotNull()

    val currenciesAndAccounts = validTransactions.map { transaction ->
        val currency = CurrencyService.detectCurrency(transaction.currencySymbol)
        val account = Config.currencyAccounts[currency]
            ?: throw RuntimeException("No account configured for currency $currency")
        Triple(transaction, currency, account)
    }

    val sourceAccount = currenciesAndAccounts.first().third

    val splits = currenciesAndAccounts.map { (transaction, currency, _) ->
        transaction.toTransactionSplit(currency, sourceAccount)
    }

    val descriptions = currenciesAndAccounts.map { (transaction, _, _) -> transaction.description }
    val groupTitle = if (descriptions.toSet().size == 1) {
        descriptions.first()
    } else {
        descriptions.mapIndexed { index, desc -> "${index + 1}. $desc" }.joinToString(" | ")
    }

    val transactionRequest = TransactionRequest(
        groupTitle = groupTitle,
        transactions = splits
    )

    val transactionResponse = FireflyApiClient.createTransaction(transactionRequest)
    val transactionId = transactionResponse.data.id

    val journalIds = transactionResponse.data.attributes.transactions.mapNotNull { it.transactionJournalId }

    photoBytes.forEachIndexed { index, bytes ->
        if (index < journalIds.size) {
            val transaction = validTransactions[index]
            FireflyApiClient.createAndUploadAttachment(
                transactionJournalId = journalIds[index],
                filename = "forte_transaction_${transaction.transactionNumber}_split${index + 1}.jpg",
                title = "Split ${index + 1} - Forte Transaction Photo",
                fileBytes = bytes,
                notes = null
            )
        }
    }

    val successMessage = buildString {
        appendLine("✅ Split транзакция успешно создана в Firefly III")
        appendLine()
        currenciesAndAccounts.forEachIndexed { index, (transaction, currency, _) ->
            appendLine("📝 Split ${index + 1}: ${transaction.description}")
            appendLine("💰 ${transaction.amount} $currency")
            if (index < currenciesAndAccounts.size - 1) {
                appendLine()
            }
        }
        appendLine()
        append("🔢 ID: $transactionId")
    }

    bot.sendMessage(
        chatId,
        successMessage,
        linkPreviewOptions = LinkPreviewOptions.Disabled,
        replyMarkup = createBudgetKeyboard(transactionId, Budget.MAIN)
    )
}

private fun ForteTransaction.toTransactionSplit(
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
        append("🔢 ID: ${transactionResponse.data.id}")
    }

    bot.sendMessage(chatId, successMessage, linkPreviewOptions = LinkPreviewOptions.Disabled, replyMarkup = createBudgetKeyboard(transactionResponse.data.id, Budget.MAIN))

    return transactionResponse.data.id
}
