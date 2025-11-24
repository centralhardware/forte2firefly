package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import me.centralhardware.forte2firefly.model.Budget

fun createBudgetKeyboard(transactionId: String, currentBudget: Budget): InlineKeyboardMarkup {
    return InlineKeyboardMarkup(
        keyboard = listOf(
            listOf(
                CallbackDataInlineKeyboardButton(
                    text = "${currentBudget.emoji} ${currentBudget.budgetName}",
                    callbackData = "budget:$transactionId:${currentBudget.budgetName}"
                )
            )
        )
    )
}

fun Double.format(digits: Int = 2): String {
    return "%.${digits}f".format(this)
}
