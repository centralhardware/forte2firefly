package me.centralhardware.forte2firefly.service

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.debug
import dev.inmo.kslog.common.info
import dev.inmo.kslog.common.warning
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class RestCountriesResponse(
    val name: RestCountriesName,
    val currencies: Map<String, RestCountriesCurrency>? = null
)

@Serializable
data class RestCountriesName(
    val common: String,
    val official: String? = null
)

@Serializable
data class RestCountriesCurrency(
    val name: String,
    val symbol: String? = null
)

object CountryToCurrencyMapper {

    private const val REST_COUNTRIES_API = "https://restcountries.com/v3.1"

    private val cache = ConcurrentHashMap<String, String>()

    suspend fun getCurrencyByCountry(country: String?): String? {
        if (country.isNullOrBlank()) {
            KSLog.warning("Empty country name, cannot determine currency")
            return null
        }

        cache[country]?.let { cached ->
            KSLog.debug("Using cached currency for $country: $cached")
            return cached
        }

        return try {
            val currency = fetchCurrencyFromApi(country)
            cache[country] = currency
            KSLog.info("Fetched currency for $country from API: $currency")
            currency
        } catch (e: Exception) {
            KSLog.warning("Failed to fetch currency for $country from API: ${e.message}")
            null
        }
    }

    private suspend fun fetchCurrencyFromApi(country: String): String {
        val url = "$REST_COUNTRIES_API/name/${country.encodeURLPath()}"

        KSLog.debug("Fetching currency from REST Countries API: $url")

        val response = HttpClientFactory.defaultClient.get(url) {
            accept(ContentType.Application.Json)
        }

        if (response.status != HttpStatusCode.OK) {
            throw IllegalStateException("REST Countries API returned ${response.status}")
        }

        val countries: List<RestCountriesResponse> = response.body()

        if (countries.isEmpty()) {
            throw IllegalStateException("No countries found for: $country")
        }

        val firstCountry = countries.first()
        val currencies = firstCountry.currencies

        if (currencies.isNullOrEmpty()) {
            throw IllegalStateException("No currencies found for country: $country")
        }

        val currencyCode = currencies.keys.first()

        KSLog.debug("Found currency code: $currencyCode for country: ${firstCountry.name.common}")

        return currencyCode
    }

    fun clearCache() {
        cache.clear()
        KSLog.info("Currency cache cleared")
    }

    fun getCacheSize(): Int = cache.size
}
