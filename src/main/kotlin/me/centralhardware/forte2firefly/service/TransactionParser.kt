package me.centralhardware.forte2firefly.service

import me.centralhardware.forte2firefly.model.ForteTransaction
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

class TransactionParser {
    private val logger = LoggerFactory.getLogger(TransactionParser::class.java)

    
    fun parseTransaction(text: String): ForteTransaction? {
        try {
            logger.info("Parsing transaction text: $text")

            val lines = text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val description = findDescription(lines)
            if (description == null) {
                logger.warn("Could not find description")
                return null
            }

            val (amount, currencySymbol) = findAmount(lines) ?: run {
                logger.warn("Could not find amount")
                return null
            }

            val dateTime = findDateTime(lines) ?: run {
                logger.warn("Could not find date time")
                return null
            }

            val from = findFrom(lines) ?: run {
                logger.warn("Could not find from (card)")
                return null
            }

            val transactionNumber = findTransactionNumber(lines) ?: run {
                logger.warn("Could not find transaction number")
                return null
            }

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
        val purchaseIndex = lines.indexOfFirst { it.contains("Purchase", ignoreCase = true) }
        if (purchaseIndex >= 0 && purchaseIndex + 1 < lines.size) {
            return lines[purchaseIndex + 1]
        }

        return lines.firstOrNull { line ->
            line.contains(Regex("[A-Z]{2,}")) &&
                    !line.contains("Purchase", ignoreCase = true) &&
                    !line.contains("Card", ignoreCase = true) &&
                    !line.contains(Regex("^\\d{2}:\\d{2}")) &&
                    !line.contains("processing", ignoreCase = true) &&
                    line.length > 3
        }
    }

    private fun findAmount(lines: List<String>): Pair<String, String>? {
        logger.debug("Searching for amount in lines:")

        for ((index, line) in lines.withIndex()) {
            logger.debug("Line $index: '$line'")

            if (line.matches(Regex("^\\d{2}:\\d{2}")) ||
                line.contains("Purchase", ignoreCase = true) ||
                line.matches(Regex("^\\d+$"))) { // просто число без валюты
                logger.debug("  -> Skipped (time/UI pattern)")
                continue
            }

            val amountMatch = Regex("""(-\d+[,.]?\d*)\s*([^\d\s:]+)""").find(line)
            if (amountMatch != null) {
                val amount = amountMatch.groupValues[1].replace(",", ".")
                val currencySymbol = amountMatch.groupValues[2].trim()
                logger.debug("  -> Found negative match: amount='$amount', currency='$currencySymbol'")

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

        for ((_, line) in lines.withIndex()) {
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
        val dateTimeIndex = lines.indexOfFirst { it.contains("Date and time", ignoreCase = true) }
        if (dateTimeIndex >= 0 && dateTimeIndex + 1 < lines.size) {
            return lines[dateTimeIndex + 1]
        }

        return lines.firstOrNull { line ->
            line.contains(Regex("\\d{2}\\s+\\w+.*\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}"))
        }
    }

    private fun findFrom(lines: List<String>): String? {
        val fromIndex = lines.indexOfFirst { it.equals("From", ignoreCase = true) }
        if (fromIndex >= 0 && fromIndex + 1 < lines.size) {
            return lines[fromIndex + 1]
        }

        return lines.firstOrNull { it.contains("Card", ignoreCase = true) }
    }

    private fun findTransactionNumber(lines: List<String>): String? {
        val transactionIndex = lines.indexOfFirst {
            it.contains("Transaction N", ignoreCase = true) ||
            it.contains("Transaction №", ignoreCase = true)
        }
        if (transactionIndex >= 0 && transactionIndex + 1 < lines.size) {
            val nextLine = lines[transactionIndex + 1]
            if (nextLine.matches(Regex("\\d+"))) {
                return nextLine
            }
        }

        return lines.firstOrNull { it.matches(Regex("\\d{10,}")) }
    }

    private fun findTransactionAmount(lines: List<String>): String? {
        val amountIndex = lines.indexOfFirst { it.contains("Transaction amount", ignoreCase = true) }
        if (amountIndex >= 0 && amountIndex + 1 < lines.size) {
            val nextLine = lines[amountIndex + 1]
            val numberMatch = Regex("""(\d+[.,]?\d*)""").find(nextLine)
            if (numberMatch != null) {
                return numberMatch.groupValues[1].replace(",", ".")
            }
        }

        if (amountIndex >= 0) {
            for (i in amountIndex + 1 until minOf(amountIndex + 4, lines.size)) {
                val line = lines[i]
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
            val cleanedDate = forteDateTime.replace("'s", "").trim()

            logger.debug("Parsing date: '$cleanedDate'")

            val inputFormatter = DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("dd MMMM yyyy HH:mm:ss")
                .toFormatter(java.util.Locale.ENGLISH)

            val dateTime = LocalDateTime.parse(cleanedDate, inputFormatter)

            val result = dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            logger.debug("Converted date: '$forteDateTime' -> '$result'")
            return result

        } catch (e: Exception) {
            logger.error("Error converting date: '$forteDateTime'", e)
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
