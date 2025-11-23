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
import me.centralhardware.forte2firefly.model.*
import org.slf4j.LoggerFactory

class FireflyApiClient(
    private val baseUrl: String,
    private val token: String
) {
    private val logger = LoggerFactory.getLogger(FireflyApiClient::class.java)

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
            header("Authorization", "Bearer $token")
            header("Accept", "application/vnd.api+json")
            contentType(ContentType.Application.Json)
        }

        defaultRequest {
            url(baseUrl)
        }
    }

    suspend fun createTransaction(transaction: TransactionRequest): TransactionResponse {
        val response = client.post("/api/v1/transactions") {
            setBody(transaction)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Firefly API error (${response.status}): $errorBody")
            throw RuntimeException("Failed to create transaction in Firefly: ${response.status}. Response: $errorBody")
        }

        return response.body()
    }

    suspend fun createAttachment(attachment: AttachmentRequest): AttachmentResponse {
        val response = client.post("/api/v1/attachments") {
            setBody(attachment)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Firefly API error (${response.status}): $errorBody")
            throw RuntimeException("Failed to create attachment in Firefly: ${response.status}. Response: $errorBody")
        }

        return response.body()
    }

    suspend fun uploadAttachment(uploadUrl: String, fileBytes: ByteArray) {
        val response = client.post(uploadUrl) {
            setBody(fileBytes)
            contentType(ContentType.Application.OctetStream)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Firefly API error (${response.status}): $errorBody")
            throw RuntimeException("Failed to upload attachment to Firefly: ${response.status}. Response: $errorBody")
        }
    }

    suspend fun getTransaction(transactionId: String): TransactionResponse {
        val response = client.get("/api/v1/transactions/$transactionId")

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Firefly API error (${response.status}): $errorBody")
            throw RuntimeException("Failed to get transaction from Firefly: ${response.status}. Response: $errorBody")
        }

        return response.body()
    }

    suspend fun updateTransaction(transactionId: String, transaction: TransactionRequest): TransactionResponse {
        val response = client.put("/api/v1/transactions/$transactionId") {
            setBody(transaction)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Firefly API error (${response.status}): $errorBody")
            throw RuntimeException("Failed to update transaction in Firefly: ${response.status}. Response: $errorBody")
        }

        return response.body()
    }

    suspend fun getBudgetLimits(budgetName: String, start: String, end: String): BudgetLimitResponse {
        val response = client.get("/api/v1/budgets/$budgetName/limits") {
            parameter("start", start)
            parameter("end", end)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Firefly API error (${response.status}): $errorBody")
            throw RuntimeException("Failed to get budget limits from Firefly: ${response.status}. Response: $errorBody")
        }

        return response.body()
    }

    suspend fun getTransactions(start: String, end: String, type: String = "withdrawal"): TransactionListResponse {
        val response = client.get("/api/v1/transactions") {
            parameter("start", start)
            parameter("end", end)
            parameter("type", type)
        }

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Firefly API error (${response.status}): $errorBody")
            throw RuntimeException("Failed to get transactions from Firefly: ${response.status}. Response: $errorBody")
        }

        return response.body()
    }

    fun close() {
        client.close()
    }
}
