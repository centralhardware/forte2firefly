package me.centralhardware.forte2firefly.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import me.centralhardware.forte2firefly.Config
import me.centralhardware.forte2firefly.model.*
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.debug
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info

object FireflyApiClient {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }

        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
        }

        install(DefaultRequest) {
            header("Authorization", "Bearer ${Config.fireflyToken}")
            header("Accept", "application/vnd.api+json")
            contentType(ContentType.Application.Json)
        }

        defaultRequest {
            url(Config.fireflyBaseUrl)
        }
    }

    private suspend inline fun <reified T> HttpResponse.handleResponse(operationName: String): T {
        if (!status.isSuccess()) {
            val errorBody = bodyAsText()
            KSLog.error("Firefly API error (${status}): $errorBody")
            throw RuntimeException("Failed to $operationName: ${status}. Response: $errorBody")
        }
        return body()
    }

    private suspend fun HttpResponse.handleResponseUnit(operationName: String) {
        if (!status.isSuccess()) {
            val errorBody = bodyAsText()
            KSLog.error("Firefly API error (${status}): $errorBody")
            throw RuntimeException("Failed to $operationName: ${status}. Response: $errorBody")
        }
    }

    suspend fun createTransaction(transaction: TransactionRequest): TransactionResponse {
        return client.post("/api/v1/transactions") {
            setBody(transaction)
        }.handleResponse("create transaction in Firefly")
    }

    suspend fun createAndUploadAttachment(
        transactionJournalId: String,
        filename: String,
        title: String,
        fileBytes: ByteArray,
        notes: String? = null
    ) {
        val attachmentRequest = AttachmentRequest(
            filename = filename,
            attachableType = "TransactionJournal",
            attachableId = transactionJournalId,
            title = title,
            notes = notes
        )
        
        val attachmentResponse = client.post("/api/v1/attachments") {
            setBody(attachmentRequest)
        }.handleResponse<AttachmentResponse>("create attachment in Firefly")
        
        val uploadUrl = attachmentResponse.data.attributes.uploadUrl
        if (uploadUrl != null) {
            client.post(uploadUrl) {
                setBody(fileBytes)
                contentType(ContentType.Application.OctetStream)
            }.handleResponseUnit("upload attachment to Firefly")
        }
    }

    suspend fun getTransaction(transactionId: String): TransactionResponse {
        return client.get("/api/v1/transactions/$transactionId")
            .handleResponse("get transaction from Firefly")
    }

    suspend fun updateTransaction(transactionId: String, transaction: TransactionRequest): TransactionResponse {
        return client.put("/api/v1/transactions/$transactionId") {
            setBody(transaction)
        }.handleResponse("update transaction in Firefly")
    }

    suspend fun getBudgetLimits(budgetName: String, start: String, end: String): BudgetLimitResponse {
        return client.get("/api/v1/budgets/$budgetName/limits") {
            parameter("start", start)
            parameter("end", end)
        }.handleResponse("get budget limits from Firefly")
    }

    suspend fun getTransactions(start: String, end: String, type: String = "withdrawal"): TransactionListResponse {
        val allTransactions = mutableListOf<TransactionData>()
        var page = 1
        var hasMorePages = true

        while (hasMorePages) {
            val response = client.get("/api/v1/transactions") {
                parameter("start", start)
                parameter("end", end)
                parameter("type", type)
                parameter("page", page)
            }
            val pageResponse = response.handleResponse<TransactionListResponse>("get transactions from Firefly")
            
            allTransactions.addAll(pageResponse.data)
            
            val totalPages = pageResponse.meta?.pagination?.totalPages ?: 1
            hasMorePages = page < totalPages
            page++

            KSLog.debug("Fetched page $page/$totalPages with ${pageResponse.data.size} transactions")
        }

        KSLog.info("Fetched total ${allTransactions.size} transactions")
        return TransactionListResponse(data = allTransactions)
    }

    suspend fun getBudgets(): BudgetListResponse {
        return client.get("/api/v1/budgets").handleResponse("get budgets from Firefly")
    }

}
