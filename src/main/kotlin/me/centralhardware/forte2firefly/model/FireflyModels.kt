package me.centralhardware.forte2firefly.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TransactionRequest(
    @SerialName("error_if_duplicate_hash") val errorIfDuplicateHash: Boolean = true,
    @SerialName("apply_rules") val applyRules: Boolean = true,
    @SerialName("fire_webhooks") val fireWebhooks: Boolean = true,
    val transactions: List<TransactionSplit>
)

@Serializable
data class TransactionSplit(
    val type: String,  // Required field, no default
    val date: String,
    val amount: String,
    val description: String,
    @SerialName("source_name") val sourceName: String? = null,
    @SerialName("destination_name") val destinationName: String? = null,
    @SerialName("currency_code") val currencyCode: String,  // Required field, no default
    @SerialName("foreign_amount") val foreignAmount: String? = null,
    @SerialName("foreign_currency_code") val foreignCurrencyCode: String? = null,
    @SerialName("external_id") val externalId: String? = null,
    val notes: String? = null,
    val tags: List<String>? = null
)

@Serializable
data class TransactionResponse(
    val data: TransactionData
)

@Serializable
data class TransactionData(
    val type: String,
    val id: String,
    val attributes: TransactionAttributes
)

@Serializable
data class TransactionAttributes(
    val transactions: List<TransactionSplitResponse>
)

@Serializable
data class TransactionSplitResponse(
    @SerialName("transaction_journal_id") val transactionJournalId: String,
    val type: String,
    val date: String,
    val amount: String,
    val description: String,
    @SerialName("source_name") val sourceName: String?,
    @SerialName("destination_name") val destinationName: String?,
    @SerialName("currency_code") val currencyCode: String? = null,
    @SerialName("foreign_amount") val foreignAmount: String? = null,
    @SerialName("foreign_currency_code") val foreignCurrencyCode: String? = null,
    @SerialName("external_id") val externalId: String? = null,
    val notes: String? = null
)

@Serializable
data class AttachmentRequest(
    val filename: String,
    @SerialName("attachable_type") val attachableType: String,
    @SerialName("attachable_id") val attachableId: String,
    val title: String? = null,
    val notes: String? = null
)

@Serializable
data class AttachmentResponse(
    val data: AttachmentData
)

@Serializable
data class AttachmentData(
    val type: String,
    val id: String,
    val attributes: AttachmentAttributes
)

@Serializable
data class AttachmentAttributes(
    @SerialName("attachable_id") val attachableId: String,
    val filename: String,
    @SerialName("upload_url") val uploadUrl: String? = null
)
