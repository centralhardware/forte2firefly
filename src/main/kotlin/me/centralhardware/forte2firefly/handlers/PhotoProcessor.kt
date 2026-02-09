package me.centralhardware.forte2firefly.handlers

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.info
import dev.inmo.kslog.common.warning
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.chat.Chat
import me.centralhardware.forte2firefly.Config
import me.centralhardware.forte2firefly.model.Budget
import me.centralhardware.forte2firefly.model.ForteTransaction
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.model.TransactionSplit
import me.centralhardware.forte2firefly.service.ConversionResult
import me.centralhardware.forte2firefly.service.CurrencyService
import me.centralhardware.forte2firefly.service.ExchangeRateService
import me.centralhardware.forte2firefly.service.FireflyApiClient
import me.centralhardware.forte2firefly.service.OCRService
import me.centralhardware.forte2firefly.service.TransactionParser

suspend fun ForteTransaction.toTransactionSplit(
    detectedCurrency: String,
    defaultCurrency: String?
): TransactionSplitResult {
    val conversionResult = ExchangeRateService.convertToUSD(amount, detectedCurrency)

    val (finalAmount, finalCurrency, finalForeignAmount, finalForeignCurrency, finalSourceAccount) =
        when (conversionResult) {
            is ConversionResult.Success -> {
                KSLog.info("Converted $amount $detectedCurrency → ${conversionResult.convertedAmount} USD")
                // При успешной конвертации используем USD счет и сохраняем оригинальную сумму в foreign amount
                val usdAccount = Config.currencyAccounts["USD"]
                    ?: throw RuntimeException("No USD account configured for currency conversion")
                CurrencyDataWithAccount(
                    amount = ExchangeRateService.formatAmount(conversionResult.convertedAmount),
                    currencyCode = "USD",
                    foreignAmount = amount,
                    foreignCurrencyCode = detectedCurrency,
                    sourceAccount = usdAccount
                )
            }

            is ConversionResult.ApiFailed -> {
                KSLog.warning("Exchange API failed, saving in $detectedCurrency")
                // При ошибке конвертации используем счет оригинальной валюты
                val originalAccount = Config.currencyAccounts[detectedCurrency]
                    ?: throw RuntimeException("No account configured for currency $detectedCurrency")
                val foreignCurrency = if (transactionAmount != null) defaultCurrency else null
                CurrencyDataWithAccount(
                    amount = amount,
                    currencyCode = detectedCurrency,
                    foreignAmount = transactionAmount,
                    foreignCurrencyCode = foreignCurrency,
                    sourceAccount = originalAccount
                )
            }

            is ConversionResult.NoConversionNeeded -> {
                // USD или валюта без конвертации - используем счет этой валюты
                val originalAccount = Config.currencyAccounts[detectedCurrency]
                    ?: throw RuntimeException("No account configured for currency $detectedCurrency")
                val foreignCurrency = if (transactionAmount != null) defaultCurrency else null
                CurrencyDataWithAccount(
                    amount = amount,
                    currencyCode = detectedCurrency,
                    foreignAmount = transactionAmount,
                    foreignCurrencyCode = foreignCurrency,
                    sourceAccount = originalAccount
                )
            }
        }

    val tags = mccCode?.let { listOf("mcc:$it") }

    val split = TransactionSplit(
        type = "withdrawal",
        date = TransactionParser.convertToFireflyDate(dateTime),
        amount = finalAmount,
        description = description,
        sourceName = finalSourceAccount,
        destinationName = description,
        currencyCode = finalCurrency,
        foreignAmount = finalForeignAmount,
        foreignCurrencyCode = finalForeignCurrency,
        externalId = transactionNumber,
        notes = null,
        budgetName = Budget.MAIN.budgetName,
        tags = tags
    )

    return TransactionSplitResult(split, conversionResult)
}

private data class CurrencyDataWithAccount(
    val amount: String,
    val currencyCode: String,
    val foreignAmount: String?,
    val foreignCurrencyCode: String?,
    val sourceAccount: String
)

data class TransactionSplitResult(
    val split: TransactionSplit,
    val conversionResult: ConversionResult
)

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
    val defaultCurrency = CurrencyService.getDefaultCurrency()
    val splitResult = transaction.toTransactionSplit(detectedCurrency, defaultCurrency)

    val transactionRequest = TransactionRequest(
        transactions = listOf(splitResult.split)
    )

    KSLog.info("Creating transaction with: amount=${splitResult.split.amount}, currency=${splitResult.split.currencyCode}, foreignAmount=${splitResult.split.foreignAmount}, foreignCurrency=${splitResult.split.foreignCurrencyCode}")

    val transactionResponse = FireflyApiClient.createTransaction(transactionRequest)

    val savedTransaction = transactionResponse.data.attributes.transactions.first()
    KSLog.info("Firefly returned: amount=${savedTransaction.amount}, currency=${savedTransaction.currencyCode}, foreignAmount=${savedTransaction.foreignAmount}, foreignCurrency=${savedTransaction.foreignCurrencyCode}")

    val journalId = savedTransaction.transactionJournalId
        ?: throw RuntimeException("Transaction journal ID is missing")

    FireflyApiClient.createAndUploadAttachment(
        transactionJournalId = journalId,
        filename = "forte_transaction_${transaction.transactionNumber}_split1.jpg",
        title = "Split 1 - Forte Transaction Photo",
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

        when (val result = splitResult.conversionResult) {
            is ConversionResult.Success -> {
                val formattedAmount = ExchangeRateService.formatAmount(result.convertedAmount)
                val reverseRate = 1.0 / result.conversionRate
                val formattedRate = String.format("%.4f", reverseRate)
                appendLine("🔄 Сконвертировано: $formattedAmount USD")
                appendLine("Курс: 1 USD = $formattedRate ${result.originalCurrency}")
            }
            is ConversionResult.ApiFailed -> {
                appendLine("⚠️ Конвертация в USD недоступна, сохранено в $detectedCurrency")
            }
            is ConversionResult.NoConversionNeeded -> {
                // Ничего не добавляем
            }
        }

        transaction.transactionAmount?.let {
            if (defaultCurrency != null) {
                appendLine("💵 В $defaultCurrency: $it")
            }
        }
        if (progressPrefix.isEmpty()) {
            appendLine("🏦 Счёт: ${splitResult.split.sourceName}")
            appendLine("📅 Дата: ${transaction.dateTime.toLocalDateTime()}")
        }
        append("🔢 ID транзакции: ${transactionResponse.data.id}, Journal: $journalId")
    }

    val sentMessage = bot.sendMessage(chatId, successMessage, linkPreviewOptions = LinkPreviewOptions.Disabled, replyMarkup = createBudgetKeyboard(transactionResponse.data.id, Budget.MAIN))

    updateBudgetAfterRules(transactionResponse.data.id, sentMessage)

    return transactionResponse.data.id
}
