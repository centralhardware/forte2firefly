package me.centralhardware.forte2firefly

object Config {
    val fireflyBaseUrl: String = System.getenv("FIREFLY_BASE_URL")
        ?: throw IllegalArgumentException("FIREFLY_BASE_URL environment variable is not set")
    
    val fireflyToken: String = System.getenv("FIREFLY_TOKEN")
        ?: throw IllegalArgumentException("FIREFLY_TOKEN environment variable is not set")
    
    val defaultCurrency: String = System.getenv("DEFAULT_CURRENCY") ?: "MYR"
    
    val accountUSD: String = System.getenv("ACCOUNT_USD")
        ?: throw IllegalArgumentException("ACCOUNT_USD environment variable is not set")
    
    val accountEUR: String = System.getenv("ACCOUNT_EUR")
        ?: throw IllegalArgumentException("ACCOUNT_EUR environment variable is not set")
    
    val accountKZT: String = System.getenv("ACCOUNT_KZT")
        ?: throw IllegalArgumentException("ACCOUNT_KZT environment variable is not set")
    
    val tessdataPrefix: String = System.getenv("TESSDATA_PREFIX") ?: "/usr/share/tesseract-ocr/5/tessdata/"
    
    val currencyAccounts: Map<String, String> = mapOf(
        "USD" to accountUSD,
        "EUR" to accountEUR,
        "KZT" to accountKZT
    )
}
