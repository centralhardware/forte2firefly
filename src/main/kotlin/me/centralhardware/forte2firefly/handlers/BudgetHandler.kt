package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.extensions.api.answers.answer
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.types.message.content.TextContent
import me.centralhardware.forte2firefly.model.Budget
import me.centralhardware.forte2firefly.model.TransactionRequest
import me.centralhardware.forte2firefly.service.FireflyApiClient
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val logger = LoggerFactory.getLogger("BudgetHandler")

fun BehaviourContext.registerBudgetHandler(
    fireflyClient: FireflyApiClient
) {
    onMessageDataCallbackQuery(
        initialFilter = { it.data.startsWith("budget:") }
    ) { query ->
        try {
            val parts = query.data.split(":")
            if (parts.size != 3) {
                logger.error("Invalid callback data format: ${query.data}")
                return@onMessageDataCallbackQuery
            }

            val transactionId = parts[1]
            val currentBudgetName = parts[2]
            val currentBudget = Budget.fromNameOrDefault(currentBudgetName)
            val newBudget = currentBudget.getNext()

            val transaction = fireflyClient.getTransaction(transactionId)
            val currentSplit = transaction.data.attributes.transactions.first()

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val changeLog = "[$timestamp] Бюджет изменен: ${currentBudget.budgetName} → ${newBudget.budgetName}"
            val updatedNotes = if (currentSplit.notes.isNullOrBlank()) {
                changeLog
            } else {
                "${currentSplit.notes}\n$changeLog"
            }

            val updatedSplit = currentSplit.copy(
                notes = updatedNotes,
                budgetName = newBudget.budgetName
            )

            val updateRequest = TransactionRequest(
                transactions = listOf(updatedSplit)
            )

            fireflyClient.updateTransaction(transactionId, updateRequest)

            answer(query, "Бюджет изменен на ${newBudget.budgetName}")

            val queryMessage = query.message
            if (queryMessage.content is TextContent) {
                @Suppress("UNCHECKED_CAST")
                edit(
                    queryMessage as dev.inmo.tgbotapi.types.message.abstracts.ContentMessage<TextContent>,
                    queryMessage.content.text,
                    replyMarkup = createBudgetKeyboard(transactionId, newBudget)
                )
            }

        } catch (e: Exception) {
            logger.error("Error handling budget callback", e)
            try {
                answer(query, "Ошибка при изменении бюджета")
            } catch (answerError: Exception) {
                logger.error("Error answering callback query", answerError)
            }
        }
    }
}
