package me.centralhardware.forte2firefly.model

data class ForteTransaction(
    val description: String,
    val amount: String,
    val currencySymbol: String,
    val dateTime: String,
    val from: String,
    val transactionNumber: String,
    val transactionAmount: String?
)
