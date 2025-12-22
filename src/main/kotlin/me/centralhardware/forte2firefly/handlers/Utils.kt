package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import me.centralhardware.forte2firefly.model.Budget

fun createBudgetKeyboard(transactionId: String, currentBudget: Budget): InlineKeyboardMarkup {
    val displayName = currentBudget.budgetName ?: "none"
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

fun String.formatAmount(): String {
    return toDoubleOrNull()?.toBigDecimal()?.stripTrailingZeros()?.toPlainString() ?: this
}
