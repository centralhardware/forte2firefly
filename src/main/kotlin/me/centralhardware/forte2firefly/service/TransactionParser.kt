package me.centralhardware.forte2firefly.service

import me.centralhardware.forte2firefly.model.ForteTransaction
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.debug
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info
import dev.inmo.kslog.common.warning
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

object TransactionParser {

    fun parseTransaction(text: String): ForteTransaction? {
        try {
            KSLog.info("Parsing transaction text: $text")

            val lines = text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val description = findDescription(lines)
            if (description == null) {
                KSLog.warning("Could not find description")
                return null
            }

            val (amount, currencySymbol) = findAmount(lines) ?: run {
                KSLog.warning("Could not find amount")
                return null
            }

            val dateTime = findDateTime(lines) ?: run {
                KSLog.warning("Could not find date time")
                return null
            }

            val from = findFrom(lines) ?: run {
                KSLog.warning("Could not find from (card)")
                return null
            }

            val transactionNumber = findTransactionNumber(lines) ?: run {
                KSLog.warning("Could not find transaction number")
                return null
            }

            val transactionAmount = findTransactionAmount(lines)
            if (transactionAmount == null) {
                KSLog.info("Transaction amount not found (no currency conversion)")
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

            KSLog.info("Successfully parsed transaction: $transaction")
            return transaction

        } catch (e: Exception) {
            KSLog.error("Error parsing transaction", e)
            return null
        }
    }

    private fun findDescription(lines: List<String>): String? {
        val purchaseIndex = lines.indexOfFirst { it.contains("Purchase", ignoreCase = true) }
        if (purchaseIndex >= 0) {
            // Ищем первую подходящую строку после "Purchase"
            for (i in (purchaseIndex + 1) until lines.size) {
                val line = lines[i]
                // Пропускаем артефакты OCR, символы и строки с суммами
                if (line.length > 3 &&
                    !line.matches(Regex("^[a-z]$")) && // пропускаем одиночные буквы
                    !line.contains(Regex("^[©<>()8]")) && // пропускаем символы и артефакты OCR
                    !line.matches(Regex("^\\d+$")) && // пропускаем строки только с цифрами
                    !line.matches(Regex("^-.*[₸$€£¥₽]")) && // пропускаем строки с суммами (начинаются с минуса и содержат валюту)
                    line.contains(Regex("[A-Z]"))) { // должна содержать хотя бы одну заглавную букву
                    return line
                }
            }
        }

        return lines.firstOrNull { line ->
            line.contains(Regex("[A-Z]+")) &&
                    !line.contains("Purchase", ignoreCase = true) &&
                    !line.contains("Card", ignoreCase = true) &&
                    !line.contains(Regex("^\\d{2}:\\d{2}")) &&
                    !line.contains("processing", ignoreCase = true) &&
                    line.length > 3
        }
    }

    private fun findAmount(lines: List<String>): Pair<String, String>? {
        KSLog.debug("Searching for amount in lines:")

        for ((index, line) in lines.withIndex()) {
            KSLog.debug("Line $index: '$line'")

            if (line.matches(Regex("^\\d{2}:\\d{2}")) ||
                line.contains("Purchase", ignoreCase = true) ||
                line.matches(Regex("^\\d+$"))) { // просто число без валюты
                KSLog.debug("  -> Skipped (time/UI pattern)")
                continue
            }

            val amountMatch = Regex("""(-[\d\s]+[,.]?\d*)\s*([^\d\s:]+)""").find(line)
            if (amountMatch != null) {
                val amount = amountMatch.groupValues[1].replace(" ", "").replace(",", ".")
                val currencySymbol = amountMatch.groupValues[2].trim()
                KSLog.debug("  -> Found negative match: amount='$amount', currency='$currencySymbol'")

                if (currencySymbol.length in 1..3 && currencySymbol != ":") {
                    val amountValue = amount.removePrefix("-").toDoubleOrNull()
                    KSLog.debug("  -> Amount value: $amountValue")
                    if (amountValue != null && amountValue > 0) {
                        KSLog.info("Found amount: $amount $currencySymbol")
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
                        KSLog.info("Found amount: $amount $currencySymbol")
                        return Pair(amount, currencySymbol)
                    }
                }
            }
        }

        KSLog.warning("Amount not found in text")
        return null
    }

    private fun findDateTime(lines: List<String>): ZonedDateTime? {
        val dateTimeStr = run {
            val dateTimeIndex = lines.indexOfFirst { it.contains("Date and time", ignoreCase = true) }
            if (dateTimeIndex >= 0 && dateTimeIndex + 1 < lines.size) {
                lines[dateTimeIndex + 1]
            } else {
                // Поддерживаем OCR артефакты: O вместо 0, l вместо 1 и т.д.
                lines.firstOrNull { line ->
                    line.contains(Regex("[O\\d]{2}\\s+\\w+.*\\d{4}\\s+\\d{2}:\\d{2}:\\d{2}"))
                }
            }
        } ?: return null

        return parseForteDateTime(dateTimeStr)
    }

    private fun parseForteDateTime(forteDateTime: String): ZonedDateTime? {
        return try {
            // Заменяем OCR артефакты: "O" на "0", убираем "'s"
            val cleanedDate = forteDateTime
                .replace("'s", "")
                .replace(Regex("^O"), "0") // O09 -> 009, O6 -> 06
                .replace(Regex("^0+(\\d{2})"), "$1") // 009 -> 09
                .trim()

            val inputFormatter = DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("dd MMMM yyyy HH:mm:ss")
                .toFormatter(java.util.Locale.ENGLISH)

            val localDateTime = LocalDateTime.parse(cleanedDate, inputFormatter)

            // Время на фото в казахстанской зоне (Asia/Almaty)
            val almatyZone = ZoneId.of("Asia/Almaty")
            ZonedDateTime.of(localDateTime, almatyZone)
        } catch (e: Exception) {
            KSLog.error("Error parsing date: '$forteDateTime'", e)
            null
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

    fun convertToFireflyDate(zonedDateTime: ZonedDateTime): String {
        // Конвертируем из Asia/Almaty в UTC
        val utcZone = ZoneId.of("UTC")
        val utcTime = zonedDateTime.withZoneSameInstant(utcZone)
        
        // Добавляем 1 час, так как Firefly показывает на 1 час раньше
        val adjustedTime = utcTime.plusHours(1)
        
        val result = adjustedTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        KSLog.debug("Converted date: ${zonedDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} (${zonedDateTime.zone}) -> ${utcTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} (UTC) -> $result (UTC+1h for Firefly)")
        return result
    }

    fun detectCurrency(currencySymbol: String): String {
        return when (currencySymbol) {
            "$" -> "USD"
            "€" -> "EUR"
            "£" -> "GBP"
            "¥" -> "JPY"
            "₽" -> "RUB"
            "₸", "T" -> "KZT" // OCR часто распознает ₸ как T
            "RM" -> "MYR"
            else -> {
                KSLog.warning("Unknown currency symbol: $currencySymbol, defaulting to USD")
                "USD"
            }
        }
    }
}
