package me.centralhardware.forte2firefly.handlers

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.sendMessage
import dev.inmo.tgbotapi.types.LinkPreviewOptions
import dev.inmo.tgbotapi.types.chat.Chat
import me.centralhardware.forte2firefly.model.Budget
import me.centralhardware.forte2firefly.service.FireflyApiClient
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

private val logger = LoggerFactory.getLogger("BudgetStats")

suspend fun generateBudgetStats(chatId: Chat, bot: TelegramBot) {
    try {
        val now = LocalDate.now()
        val yearMonth = YearMonth.from(now)
        val startOfMonth = yearMonth.atDay(1)
        val endOfMonth = yearMonth.atEndOfMonth()

        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        val start = startOfMonth.format(dateFormatter)
        val end = endOfMonth.format(dateFormatter)

        // Получаем ID бюджета по имени
        val budgetId = try {
            val budgets = FireflyApiClient.getBudgets()
            budgets.data.find { it.attributes.name == Budget.MAIN.budgetName }?.id
        } catch (e: Exception) {
            logger.warn("Failed to fetch budgets: ${e.message}")
            null
        }

        // Получаем лимит бюджета в USD (может не существовать)
        val budgetLimit = if (budgetId != null) {
            try {
                val budgetLimits = FireflyApiClient.getBudgetLimits(budgetId, start, end)
                budgetLimits.data.find { it.attributes.currencyCode == "USD" }?.attributes
            } catch (e: Exception) {
                logger.warn("Budget '${Budget.MAIN.budgetName}' has no limits: ${e.message}")
                null
            }
        } else {
            null
        }

        // Получаем потраченную сумму из лимита бюджета (если есть)
        val totalSpent = budgetLimit?.spent
            ?.find { it.currencyCode == "USD" }
            ?.sum?.toDoubleOrNull()?.absoluteValue ?: 0.0

        // Получаем все транзакции за месяц для топ-5 категорий
        val transactions = FireflyApiClient.getTransactions(start, end)

        // Фильтруем по бюджету main
        val mainBudgetTransactions = transactions.data.filter { transaction ->
            transaction.attributes.transactions.any {
                it.budgetName == Budget.MAIN.budgetName
            }
        }

        val daysInMonth = yearMonth.lengthOfMonth()
        val daysPassed = ChronoUnit.DAYS.between(startOfMonth, now).toInt() // Дни без сегодня
        val daysRemaining = daysInMonth - daysPassed - 1 // Дни после сегодня

        // Траты за сегодня
        val todayTransactions = transactions.data.filter { transaction ->
            transaction.attributes.transactions.any {
                it.budgetName == Budget.MAIN.budgetName &&
                it.date?.startsWith(now.format(dateFormatter)) == true
            }
        }
        val todaySpent = todayTransactions
            .flatMap { it.attributes.transactions }
            .filter { it.budgetName == Budget.MAIN.budgetName && it.currencyCode == "USD" }
            .sumOf { it.amount.toDoubleOrNull()?.absoluteValue ?: 0.0 }

        val budgetAmount = budgetLimit?.amount?.toDoubleOrNull() ?: 0.0
        
        // Среднее за прошедшие дни (без сегодня)
        val spentBeforeToday = totalSpent - todaySpent
        val avgPerDay = if (daysPassed > 0) spentBeforeToday / daysPassed else 0.0
        val normalPerDay = if (daysInMonth > 0 && budgetAmount > 0) budgetAmount / daysInMonth else 0.0

        // Топ 5 категорий (destination_name) в USD
        val categorySpending = mainBudgetTransactions
            .flatMap { it.attributes.transactions }
            .filter { it.budgetName == Budget.MAIN.budgetName && it.currencyCode == "USD" }
            .groupBy { it.destinationName ?: "Без категории" }
            .mapValues { (_, splits) ->
                val total = splits.sumOf { it.amount.toDoubleOrNull()?.absoluteValue ?: 0.0 }
                val count = splits.size
                total to count
            }
            .entries
            .sortedByDescending { it.value.first }
            .take(5)

        val remaining = budgetAmount - totalSpent
        val avgPerDayRemaining = if (daysRemaining > 0) remaining / daysRemaining else 0.0

        val message = buildString {
            val monthName = yearMonth.month.name.lowercase().replaceFirstChar { it.uppercase() }
            appendLine("📊 Статистика бюджета \"${Budget.MAIN.budgetName}\" за $monthName ${yearMonth.year}")
            appendLine()

            if (budgetLimit != null && budgetAmount > 0) {
                appendLine("💰 Лимит бюджета: ${budgetAmount.format()} USD")
                appendLine("📉 Потрачено: ${totalSpent.format()} USD (${(totalSpent / budgetAmount * 100).format(1)}%)")
                appendLine("💵 Осталось: ${remaining.format()} USD")
            } else if (budgetId != null) {
                appendLine("⚠️ У бюджета \"${Budget.MAIN.budgetName}\" не установлен лимит на текущий месяц")
                appendLine("📉 Потрачено: ${totalSpent.format()} USD")
                appendLine()
                appendLine("💡 Установите лимит бюджета в Firefly III для полной статистики")
            } else {
                appendLine("⚠️ Бюджет \"${Budget.MAIN.budgetName}\" не найден в Firefly III")
                appendLine("📉 Потрачено: ${totalSpent.format()} USD")
                appendLine()
                appendLine("💡 Создайте бюджет \"${Budget.MAIN.budgetName}\" в Firefly III для полной статистики")
            }

            appendLine()
            appendLine("📅 Дней прошло: $daysPassed/$daysInMonth")
            appendLine("📆 Сегодня потрачено: ${todaySpent.format()} USD")
            appendLine("⏳ Дней осталось: $daysRemaining")
            appendLine()

            if (budgetAmount > 0) {
                appendLine("📏 Норма: ${normalPerDay.format()} USD/день")
                appendLine()

                val avgDeviation = avgPerDay - normalPerDay
                val avgDeviationPercent = if (normalPerDay > 0) (avgDeviation / normalPerDay * 100) else 0.0
                val avgDeviationSign = if (avgDeviation > 0) "+" else ""
                appendLine("📊 Средние траты за прошедшие дни: ${avgPerDay.format()} USD/день (${avgDeviationSign}${avgDeviation.format()} USD, ${avgDeviationSign}${avgDeviationPercent.format(1)}%)")

                if (daysRemaining > 0) {
                    val remainingDeviation = avgPerDayRemaining - normalPerDay
                    val remainingDeviationPercent = if (normalPerDay > 0) (remainingDeviation / normalPerDay * 100) else 0.0
                    val remainingDeviationSign = if (remainingDeviation > 0) "+" else ""
                    appendLine("💡 Доступно на будущие дни: ${avgPerDayRemaining.format()} USD/день (${remainingDeviationSign}${remainingDeviation.format()} USD, ${remainingDeviationSign}${remainingDeviationPercent.format(1)}%)")
                }
            } else {
                appendLine("📊 Средние траты за прошедшие дни: ${avgPerDay.format()} USD/день")
            }

            if (categorySpending.isNotEmpty()) {
                appendLine()
                appendLine("🏆 Топ-5 категорий:")
                categorySpending.forEachIndexed { index, (category, data) ->
                    val (total, count) = data
                    val categoryAvg = if (count > 0) total / count else 0.0
                    appendLine("${index + 1}. $category: ${total.format()} USD (${categoryAvg.format()}/транзакция, $count шт)")
                }
            }
        }

        bot.sendMessage(chatId, message, linkPreviewOptions = LinkPreviewOptions.Disabled)

    } catch (e: Exception) {
        logger.error("Error generating budget stats", e)
        bot.sendMessage(chatId, "❌ Ошибка при получении статистики: ${e.message}", linkPreviewOptions = LinkPreviewOptions.Disabled)
    }
}
