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
    val type: String,
    val date: String,
    val amount: String,
    val description: String,
    @SerialName("source_name") val sourceName: String? = null,
    @SerialName("destination_name") val destinationName: String? = null,
    @SerialName("currency_code") val currencyCode: String? = null,
    @SerialName("foreign_amount") val foreignAmount: String? = null,
    @SerialName("foreign_currency_code") val foreignCurrencyCode: String? = null,
    @SerialName("external_id") val externalId: String? = null,
    val notes: String? = null,
    val tags: List<String>? = null,
    @SerialName("budget_id") val budgetId: String? = null,
    @SerialName("budget_name") val budgetName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("zoom_level") val zoomLevel: Int? = null,
    @SerialName("transaction_journal_id") val transactionJournalId: String? = null
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
    val transactions: List<TransactionSplit>
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

@Serializable
data class BudgetLimitResponse(
    val data: List<BudgetLimitData>
)

@Serializable
data class BudgetLimitData(
    val id: String,
    val attributes: BudgetLimitAttributes
)

@Serializable
data class BudgetLimitAttributes(
    val start: String,
    val end: String,
    val amount: String,
    val spent: List<BudgetSpent>? = null,
    @SerialName("currency_code") val currencyCode: String? = null
)

@Serializable
data class BudgetSpent(
    val sum: String,
    @SerialName("currency_code") val currencyCode: String? = null
)

@Serializable
data class TransactionListResponse(
    val data: List<TransactionData>,
    val meta: PaginationMeta? = null,
    val links: PaginationLinks? = null
)

@Serializable
data class BudgetListResponse(
    val data: List<BudgetData>,
    val meta: PaginationMeta? = null,
    val links: PaginationLinks? = null
)

@Serializable
data class BudgetData(
    val id: String,
    val attributes: BudgetAttributes
)

@Serializable
data class BudgetAttributes(
    val name: String,
    val active: Boolean? = null
)

@Serializable
data class PaginationMeta(
    val pagination: PaginationInfo
)

@Serializable
data class PaginationInfo(
    val total: Int,
    val count: Int,
    @SerialName("per_page") val perPage: Int,
    @SerialName("current_page") val currentPage: Int,
    @SerialName("total_pages") val totalPages: Int
)

@Serializable
data class PaginationLinks(
    val self: String,
    val first: String? = null,
    val next: String? = null,
    val prev: String? = null,
    val last: String? = null
)
