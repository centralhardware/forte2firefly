package me.centralhardware.forte2firefly.model

enum class Budget(val budgetName: String, val emoji: String) {
    MAIN("main", "💰"),
    TRAVEL("travel", "✈️"),
    STUDY("study", "📚"),
    SUBSCRIPTIONS("subscriptions", "📱");

    fun getNext(): Budget {
        val values = Budget.values()
        val currentIndex = values.indexOf(this)
        return values[(currentIndex + 1) % values.size]
    }

    companion object {
        fun fromName(name: String): Budget? {
            return values().find { it.budgetName == name }
        }

        fun fromNameOrDefault(name: String?): Budget {
            return name?.let { fromName(it) } ?: MAIN
        }
    }
}
