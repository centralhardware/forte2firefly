package me.centralhardware.forte2firefly.model

enum class Budget(val budgetName: String, val emoji: String) {
    MAIN("main", "💰"),
    TRAVEL("travel", "✈️"),
    STUDY("study", "📚"),
    SUBSCRIPTIONS("subscriptions", "📱");

    fun getNext(): Budget {
        val currentIndex = entries.indexOf(this)
        return entries[(currentIndex + 1) % entries.size]
    }

    companion object {
        fun fromName(name: String): Budget? {
            return entries.find { it.budgetName == name }
        }

        fun fromNameOrDefault(name: String?): Budget {
            return name?.let { fromName(it) } ?: MAIN
        }
    }
}
