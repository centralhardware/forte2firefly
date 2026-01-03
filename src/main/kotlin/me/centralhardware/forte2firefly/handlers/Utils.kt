package me.centralhardware.forte2firefly.handlers

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import dev.inmo.tgbotapi.extensions.api.edit.edit
import dev.inmo.tgbotapi.extensions.behaviour_builder.BehaviourContext
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.message.abstracts.ContentMessage
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.centralhardware.forte2firefly.model.Budget
import me.centralhardware.forte2firefly.service.FireflyApiClient

fun createBudgetKeyboard(transactionId: String, currentBudget: Budget): InlineKeyboardMarkup {
    val displayName = currentBudget.budgetName.ifEmpty { "none" }
    return InlineKeyboardMarkup(
        keyboard = listOf(
            listOf(
                CallbackDataInlineKeyboardButton(
                    text = "${currentBudget.emoji} $displayName",
                    callbackData = "budget:$transactionId:$displayName"
                )
            )
        )
    )
}

fun Double.format(digits: Int = 2): String {
    return "%.${digits}f".format(this)
}

fun BehaviourContext.updateBudgetAfterRules(
    transactionId: String,
    message: ContentMessage<TextContent>
) {
    launch {
        try {
            delay(2000)

            val transaction = FireflyApiClient.getTransaction(transactionId)
            val actualBudgetName = transaction.data.attributes.transactions.first().budgetName
            val actualBudget = Budget.fromNameOrDefault(actualBudgetName)

            edit(
                message,
                message.content.text,
                replyMarkup = createBudgetKeyboard(transactionId, actualBudget)
            )
        } catch (e: Exception) {
            KSLog.error("Error updating budget keyboard after rules", e)
        }
    }
}
