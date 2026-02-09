package me.centralhardware.forte2firefly.service

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.info
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

    suspend fun getDefaultCurrency(): String? {
        val currentCountry = DatabaseService.getCurrentCountry()
        if (currentCountry == null) {
            KSLog.warning("No current country found in database, foreign currency will not be set")
            return null
        }

        val currency = CountryToCurrencyMapper.getCurrencyByCountry(currentCountry)
        KSLog.info("Determined default currency: $currency (country: $currentCountry)")
        return currency
    }
}
