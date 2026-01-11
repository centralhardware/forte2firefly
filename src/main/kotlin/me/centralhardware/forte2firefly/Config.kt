package me.centralhardware.forte2firefly

object Config {
    val fireflyBaseUrl: String by lazy {
        System.getenv("FIREFLY_BASE_URL")
            ?: throw IllegalArgumentException("FIREFLY_BASE_URL environment variable is not set")
    }

    val fireflyToken: String by lazy {
        System.getenv("FIREFLY_TOKEN")
            ?: throw IllegalArgumentException("FIREFLY_TOKEN environment variable is not set")
    }

    val defaultCurrency: String = System.getenv("DEFAULT_CURRENCY") ?: "MYR"

    val exchangeRateApiKey: String? = System.getenv("EXCHANGE_RATE_API_KEY")

    val tessdataPrefix: String = System.getenv("TESSDATA_PREFIX") ?: "/usr/share/tesseract-ocr/5/tessdata/"

    val currencyAccounts: Map<String, String> = mapOf(
        "USD" to "forte solo signature (USD)",
        "EUR" to "forte solo signature (EUR)",
        "KZT" to "forte solo signature (KZT)"
    )
}
