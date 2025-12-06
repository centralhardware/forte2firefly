package me.centralhardware.forte2firefly.model

import java.time.ZonedDateTime

data class ForteTransaction(
    val description: String,
    val amount: String,
    val currencySymbol: String,
    val dateTime: ZonedDateTime,
    val from: String,
    val transactionNumber: String,
    val transactionAmount: String?,
    val mccCode: String?
)
