package me.centralhardware.forte2firefly.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExchangeRateResponse(
    val result: String,
    @SerialName("conversion_rate") val conversionRate: Double? = null,
    val rates: Map<String, Double>? = null,
    @SerialName("base_code") val baseCode: String? = null,
    @SerialName("target_code") val targetCode: String? = null,
    @SerialName("error-type") val errorType: String? = null
)
