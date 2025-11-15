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
        val firstTx = transaction.transactions.first()
        logger.info("Creating transaction: ${firstTx.description}")
        logger.info("  Source: ${firstTx.sourceName}, Destination: ${firstTx.destinationName}")

        val response = client.post("/api/v1/transactions") {
            setBody(transaction)
        }

        logger.info("Transaction created with status: ${response.status}")

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Firefly API error (${response.status}): $errorBody")
            throw RuntimeException("Failed to create transaction in Firefly: ${response.status}. Response: $errorBody")
        }

        return response.body()
    }

    suspend fun createAttachment(attachment: AttachmentRequest): AttachmentResponse {
        logger.info("Creating attachment for transaction journal: ${attachment.attachableId}")

        val response = client.post("/api/v1/attachments") {
            setBody(attachment)
        }

        logger.info("Attachment created with status: ${response.status}")

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Firefly API error (${response.status}): $errorBody")
            throw RuntimeException("Failed to create attachment in Firefly: ${response.status}. Response: $errorBody")
        }

        return response.body()
    }

    suspend fun uploadAttachment(uploadUrl: String, fileBytes: ByteArray) {
        logger.info("Uploading file to: $uploadUrl, size: ${fileBytes.size} bytes")

        val response = client.post(uploadUrl) {
            setBody(fileBytes)
            contentType(ContentType.Application.OctetStream)
        }

        logger.info("File uploaded with status: ${response.status}")

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Firefly API error (${response.status}): $errorBody")
            throw RuntimeException("Failed to upload attachment to Firefly: ${response.status}. Response: $errorBody")
        }
    }

    suspend fun getTransaction(transactionId: String): TransactionResponse {
        logger.info("Getting transaction by ID: $transactionId")

        val response = client.get("/api/v1/transactions/$transactionId")

        logger.info("Transaction retrieved with status: ${response.status}")

        if (!response.status.isSuccess()) {
            val errorBody = response.bodyAsText()
            logger.error("Firefly API error (${response.status}): $errorBody")
            throw RuntimeException("Failed to get transaction from Firefly: ${response.status}. Response: $errorBody")
        }

        return response.body()
    }

    fun close() {
        client.close()
    }
}
