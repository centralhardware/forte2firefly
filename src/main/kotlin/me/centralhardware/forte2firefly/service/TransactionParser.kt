package me.centralhardware.forte2firefly.service

import me.centralhardware.forte2firefly.model.ForteTransaction
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

class TransactionParser {
    private val logger = LoggerFactory.getLogger(TransactionParser::class.java)

    // Пример текста с фото:
    // NSK GROCER- QCM
    // -18,29 $
    // 09 november's 2025 15:37:39
    // Card Solo Visa Signature MLT **1293
    // 12165085404
    // 75.5
    
    fun parseTransaction(text: String): ForteTransaction? {
        try {
            logger.info("Parsing transaction text: $text")

            val lines = text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            // Ищем описание - обычно это строка с названием магазина (содержит буквы и может содержать дефис)
            val description = findDescription(lines)
            if (description == null) {
                logger.warn("Could not find description")
                return null
            }

            // Ищем сумму - строка с числом и символом валюты (например: "-18,29 $")
            val (amount, currencySymbol) = findAmount(lines) ?: run {
                logger.warn("Could not find amount")
                return null
            }

            // Ищем дату и время - строка после "Date and time"
            val dateTime = findDateTime(lines) ?: run {
                logger.warn("Could not find date time")
                return null
            }

            // Ищем карту - строка после "From" или содержащая "Card"
            val from = findFrom(lines) ?: run {
                logger.warn("Could not find from (card)")
                return null
            }

            // Ищем номер транзакции - длинное число (обычно 10+ цифр)
            val transactionNumber = findTransactionNumber(lines) ?: run {
                logger.warn("Could not find transaction number")
                return null
            }

            // Ищем transaction amount - число после "Transaction amount"
            val transactionAmount = findTransactionAmount(lines)
            if (transactionAmount == null) {
                logger.info("Transaction amount not found (no currency conversion)")
            }

            val transaction = ForteTransaction(
                description = description,
                amount = amount.removePrefix("-"),
                currencySymbol = currencySymbol,
                dateTime = dateTime,
                from = from,
                transactionNumber = transactionNumber,
                transactionAmount = transactionAmount
            )

            logger.info("Successfully parsed transaction: $transaction")
            return transaction

        } catch (e: Exception) {
            logger.error("Error parsing transaction", e)
            return null
        }
    }

    private fun findDescription(lines: List<String>): String? {
        // Описание мерчанта всегда идёт сразу после строки "Purchase"
        val purchaseIndex = lines.indexOfFirst { it.contains("Purchase", ignoreCase = true) }
        if (purchaseIndex >= 0 && purchaseIndex + 1 < lines.size) {
            return lines[purchaseIndex + 1]
        }

        // Fallback: ищем строку с названием магазина - обычно содержит буквы и может иметь дефис
        // Пропускаем строки с временем, Purchase, Card и другие UI элементы
        return lines.firstOrNull { line ->
            line.contains(Regex("[A-Z]{2,}")) && // содержит хотя бы 2 заглавные буквы подряд
                    !line.contains("Purchase", ignoreCase = true) &&
                    !line.contains("Card", ignoreCase = true) && // Пропускаем строки с "Card"
                    !line.contains(Regex("^\\d{2}:\\d{2}")) && // не начинается с времени
                    !line.contains("processing", ignoreCase = true) &&
                    line.length > 3
        }
    }

    private fun findAmount(lines: List<String>): Pair<String, String>? {
        // Ищем строку с суммой и валютой (например: "-18,29 $")
        // Приоритет: сначала ищем отрицательные суммы, потом положительные
        logger.debug("Searching for amount in lines:")

        // Первый проход: ищем отрицательные суммы (обычно транзакции расходов)
        for ((index, line) in lines.withIndex()) {
            logger.debug("Line $index: '$line'")

            // Пропускаем строки которые похожи на время или UI элементы
            if (line.matches(Regex("^\\d{2}:\\d{2}")) ||
                line.contains("Purchase", ignoreCase = true) ||
                line.matches(Regex("^\\d+$"))) { // просто число без валюты
                logger.debug("  -> Skipped (time/UI pattern)")
                continue
            }

            // Ищем отрицательную сумму с валютой
            val amountMatch = Regex("""(-\d+[,.]?\d*)\s*([^\d\s:]+)""").find(line)
            if (amountMatch != null) {
                val amount = amountMatch.groupValues[1].replace(",", ".")
                val currencySymbol = amountMatch.groupValues[2].trim()
                logger.debug("  -> Found negative match: amount='$amount', currency='$currencySymbol'")

                // Проверяем что это похоже на валютный символ
                if (currencySymbol.length in 1..3 && currencySymbol != ":") {
                    val amountValue = amount.removePrefix("-").toDoubleOrNull()
                    logger.debug("  -> Amount value: $amountValue")
                    if (amountValue != null && amountValue > 0) {
                        logger.info("Found amount: $amount $currencySymbol")
                        return Pair(amount, currencySymbol)
                    }
                }
            }
        }

        // Второй проход: ищем положительные суммы (если отрицательных не нашли)
        for ((index, line) in lines.withIndex()) {
            if (line.matches(Regex("^\\d{2}:\\d{2}")) ||
                line.contains("Purchase", ignoreCase = true)) {
                continue
            }

            val amountMatch = Regex("""(\d+[,.]?\d*)\s*([^\d\s:]+)""").find(line)
            if (amountMatch != null) {
                val amount = amountMatch.groupValues[1].replace(",", ".")
                val currencySymbol = amountMatch.groupValues[2].trim()

                if (currencySymbol.length in 1..3 && currencySymbol != ":") {
                    val amountValue = amount.toDoubleOrNull()
                    if (amountValue != null && amountValue > 0) {
                        logger.info("Found amount: $amount $currencySymbol")
                        return Pair(amount, currencySymbol)
                    }
                }
            }
        }

        logger.warn("Amount not found in text")
        return null
    }

    private fun findDateTime(lines: List<String>): String? {
        // Ищем дату после строки "Date and time"
        val dateTimeIndex = lines.indexOfFirst { it.contains("Date and time", ignoreCase = true) }
        if (dateTimeIndex >= 0 && dateTimeIndex + 1 < lines.size) {
            return lines[dateTimeIndex + 1]
        }

        // Fallback: ищем строку с датой (содержит месяц и год)
        return lines.firstOrNull { line ->
            line.contains(Regex("\\d{2}\\s+\\w+.*\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}"))
        }
    }

    private fun findFrom(lines: List<String>): String? {
        // Ищем карту после строки "From"
        val fromIndex = lines.indexOfFirst { it.equals("From", ignoreCase = true) }
        if (fromIndex >= 0 && fromIndex + 1 < lines.size) {
            return lines[fromIndex + 1]
        }

        // Fallback: ищем строку содержащую "Card"
        return lines.firstOrNull { it.contains("Card", ignoreCase = true) }
    }

    private fun findTransactionNumber(lines: List<String>): String? {
        // Ищем номер транзакции после "Transaction N" или "Transaction №"
        val transactionIndex = lines.indexOfFirst {
            it.contains("Transaction N", ignoreCase = true) ||
            it.contains("Transaction №", ignoreCase = true)
        }
        if (transactionIndex >= 0 && transactionIndex + 1 < lines.size) {
            val nextLine = lines[transactionIndex + 1]
            // Проверяем что это число
            if (nextLine.matches(Regex("\\d+"))) {
                return nextLine
            }
        }

        // Fallback: ищем длинное число (10+ цифр)
        return lines.firstOrNull { it.matches(Regex("\\d{10,}")) }
    }

    private fun findTransactionAmount(lines: List<String>): String? {
        // Ищем сумму транзакции после "Transaction amount"
        val amountIndex = lines.indexOfFirst { it.contains("Transaction amount", ignoreCase = true) }
        if (amountIndex >= 0 && amountIndex + 1 < lines.size) {
            val nextLine = lines[amountIndex + 1]
            // Пытаемся извлечь число
            val numberMatch = Regex("""(\d+[.,]?\d*)""").find(nextLine)
            if (numberMatch != null) {
                return numberMatch.groupValues[1].replace(",", ".")
            }
        }

        // Fallback: ищем строку с числом после строки "Transaction amount"
        // Ищем в следующих 3 строках
        if (amountIndex >= 0) {
            for (i in amountIndex + 1 until minOf(amountIndex + 4, lines.size)) {
                val line = lines[i]
                // Пытаемся найти число в строке
                val numberMatch = Regex("""(\d+[.,]?\d*)""").find(line)
                if (numberMatch != null) {
                    return numberMatch.groupValues[1].replace(",", ".")
                }
            }
        }

        return null
    }

    fun convertToFireflyDate(forteDateTime: String): String {
        try {
            // Форте может присылать даты в разных форматах:
            // "09 november's 2025 15:37:39" (с апострофом, строчная 'n')
            // "07 november 2025 12:43:11" (без апострофа, строчная 'n')
            // "09 November 2025 15:37:39" (без апострофа, заглавная 'N')
            // Очищаем от апострофа если есть
            val cleanedDate = forteDateTime.replace("'s", "").trim()

            logger.debug("Parsing date: '$cleanedDate'")

            // Парсим дату с case-insensitive форматом (поддерживает "november" и "November")
            val inputFormatter = DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("dd MMMM yyyy HH:mm:ss")
                .toFormatter(java.util.Locale.ENGLISH)

            val dateTime = LocalDateTime.parse(cleanedDate, inputFormatter)

            // Конвертируем в формат Firefly (ISO 8601)
            val result = dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            logger.debug("Converted date: '$forteDateTime' -> '$result'")
            return result

        } catch (e: Exception) {
            logger.error("Error converting date: '$forteDateTime'", e)
            // В случае ошибки возвращаем текущую дату
            val fallbackDate = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            logger.warn("Using fallback date: $fallbackDate")
            return fallbackDate
        }
    }

    fun detectCurrency(currencySymbol: String): String {
        return when (currencySymbol) {
            "$" -> "USD"
            "€" -> "EUR"
            "£" -> "GBP"
            "¥" -> "JPY"
            "₽" -> "RUB"
            "RM" -> "MYR"
            else -> {
                logger.warn("Unknown currency symbol: $currencySymbol, defaulting to USD")
                "USD"
            }
        }
    }
}
