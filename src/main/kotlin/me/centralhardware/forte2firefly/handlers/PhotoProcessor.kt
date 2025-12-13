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
import me.centralhardware.forte2firefly.service.FireflyApiClient
import me.centralhardware.forte2firefly.service.OCRService
import me.centralhardware.forte2firefly.service.TransactionParser

suspend fun BehaviourContext.processSplitTransaction(
    photoBytes: List<ByteArray>,
    chatId: Chat
) {
    // Обрабатываем все фотографии через OCR
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

    // Проверяем, что все транзакции успешно распознаны
    if (transactions.any { it == null }) {
        return
    }

    val validTransactions = transactions.filterNotNull()

    // Определяем валюты и счета для всех транзакций
    val currenciesAndAccounts = validTransactions.map { transaction ->
        val currency = OCRService.detectCurrency(transaction.currencySymbol)
        val account = Config.currencyAccounts[currency]
            ?: throw RuntimeException("No account configured for currency $currency")
        Triple(transaction, currency, account)
    }

    // Для split транзакции source account должен быть одинаковым
    // Используем счет из первой транзакции
    val sourceAccount = currenciesAndAccounts.first().third

    // Создаем splits для всех транзакций
    val splits = currenciesAndAccounts.map { (transaction, currency, _) ->
        createTransactionSplit(transaction, currency, sourceAccount)
    }

    // Формируем заголовок группы из описаний всех транзакций
    val descriptions = currenciesAndAccounts.map { (transaction, _, _) -> transaction.description }
    val groupTitle = if (descriptions.toSet().size == 1) {
        // Все описания одинаковые - используем одно
        descriptions.first()
    } else {
        // Разные описания - нумеруем каждое
        descriptions.mapIndexed { index, desc -> "${index + 1}. $desc" }.joinToString(" | ")
    }

    val transactionRequest = TransactionRequest(
        groupTitle = groupTitle,
        transactions = splits
    )

    // Создаем split транзакцию в Firefly
    val transactionResponse = FireflyApiClient.createTransaction(transactionRequest)
    val transactionId = transactionResponse.data.id

    // Прикрепляем все фотографии к транзакции
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

    // Формируем сообщение об успехе
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

private fun createTransactionSplit(
    forteTransaction: ForteTransaction,
    detectedCurrency: String,
    sourceAccount: String
): TransactionSplit {
    val foreignAmount = forteTransaction.transactionAmount
    val foreignCurrency = if (foreignAmount != null) Config.defaultCurrency else null

    val tags = buildList {
        if (forteTransaction.mccCode != null) {
            add("mcc:${forteTransaction.mccCode}")
        }
    }.takeIf { it.isNotEmpty() }

    return TransactionSplit(
        type = "withdrawal",
        date = TransactionParser.convertToFireflyDate(forteTransaction.dateTime),
        amount = forteTransaction.amount,
        description = forteTransaction.description,
        sourceName = sourceAccount,
        destinationName = forteTransaction.description,
        currencyCode = detectedCurrency,
        foreignAmount = foreignAmount,
        foreignCurrencyCode = foreignCurrency,
        externalId = forteTransaction.transactionNumber,
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
                notes = null,
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
        notes = null
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
