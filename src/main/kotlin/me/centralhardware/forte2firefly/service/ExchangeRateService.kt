package me.centralhardware.forte2firefly.service

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info
import dev.inmo.kslog.common.warning
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import me.centralhardware.forte2firefly.Config
import me.centralhardware.forte2firefly.model.ExchangeRateResponse

object ExchangeRateService {

    private val client = HttpClientFactory.defaultClient

    suspend fun convertToUSD(
        amount: String,
        sourceCurrency: String
    ): ConversionResult {
        if (sourceCurrency == "USD") {
            return ConversionResult.NoConversionNeeded
        }

        if (sourceCurrency !in listOf("EUR", "KZT")) {
            KSLog.info("Currency $sourceCurrency not configured for conversion")
            return ConversionResult.NoConversionNeeded
        }

        try {
            val rate = fetchExchangeRate(sourceCurrency, "USD")
                ?: return ConversionResult.ApiFailed(
                    originalAmount = amount,
                    originalCurrency = sourceCurrency
                )

            val originalAmount = amount.toDoubleOrNull()
                ?: return ConversionResult.ApiFailed(
                    originalAmount = amount,
                    originalCurrency = sourceCurrency
                )

            val convertedAmount = (originalAmount * rate).toString()

            KSLog.info("Converted $amount $sourceCurrency to $convertedAmount USD (rate: $rate)")

            return ConversionResult.Success(
                convertedAmount = convertedAmount,
                conversionRate = rate,
                originalAmount = amount,
                originalCurrency = sourceCurrency
            )

        } catch (e: Exception) {
            KSLog.error("Exchange rate conversion failed for $sourceCurrency -> USD", e)
            return ConversionResult.ApiFailed(
                originalAmount = amount,
                originalCurrency = sourceCurrency
            )
        }
    }

    private suspend fun fetchExchangeRate(
        from: String,
        to: String
    ): Double? {
        return try {
            val apiKey = Config.exchangeRateApiKey
            val url = if (apiKey != null) {
                "https://v6.exchangerate-api.com/v6/$apiKey/pair/$from/$to"
            } else {
                KSLog.warning("EXCHANGE_RATE_API_KEY not set, using free tier API")
                "https://open.er-api.com/v6/latest/$from"
            }

            KSLog.info("Fetching exchange rate: $from -> $to from $url")

            val response = client.get(url)

            if (!response.status.isSuccess()) {
                KSLog.error("Exchange rate API returned ${response.status}")
                return null
            }

            val exchangeResponse = response.body<ExchangeRateResponse>()

            if (exchangeResponse.result != "success") {
                KSLog.error("Exchange rate API result: ${exchangeResponse.result}")
                return null
            }

            val rate = exchangeResponse.conversionRate
                ?: exchangeResponse.rates?.get(to)

            if (rate == null) {
                KSLog.error("Could not extract conversion rate from response")
                return null
            }

            KSLog.info("Successfully fetched rate: 1 $from = $rate $to")
            rate

        } catch (e: Exception) {
            KSLog.error("Failed to fetch exchange rate for $from -> $to", e)
            null
        }
    }

    fun formatAmount(amount: String): String {
        return try {
            val value = amount.toDouble()
            String.format("%.2f", value)
        } catch (e: Exception) {
            amount
        }
    }
}

sealed class ConversionResult {
    data class Success(
        val convertedAmount: String,
        val conversionRate: Double,
        val originalAmount: String,
        val originalCurrency: String
    ) : ConversionResult()

    data class ApiFailed(
        val originalAmount: String,
        val originalCurrency: String
    ) : ConversionResult()

    data object NoConversionNeeded : ConversionResult()
}
