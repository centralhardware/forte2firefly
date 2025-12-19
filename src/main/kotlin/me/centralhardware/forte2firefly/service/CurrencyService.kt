package me.centralhardware.forte2firefly.service

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.warning

object CurrencyService {

    private val currencyMap = mapOf(
        "$" to "USD",
        "€" to "EUR",
        "£" to "GBP",
        "¥" to "JPY",
        "₽" to "RUB",
        "₸" to "KZT",
        "T" to "KZT",
        "RM" to "MYR"
    )

    fun detectCurrency(symbol: String): String {
        return currencyMap[symbol] ?: run {
            KSLog.warning("Unknown currency symbol: $symbol, defaulting to USD")
            "USD"
        }
    }
}
