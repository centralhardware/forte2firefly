package me.centralhardware.forte2firefly.model

enum class Budget(val budgetName: String, val emoji: String) {
    MAIN("main", "💰"),
    STUDY("study", "📚"),
    SUBSCRIPTIONS("subscriptions", "📱"),
    NONE("", "🚫");

    fun getNext(): Budget {
        val currentIndex = entries.indexOf(this)
        return entries[(currentIndex + 1) % entries.size]
    }

    companion object {
        fun fromName(name: String): Budget? {
            if (name == "none" || name.isEmpty()) return NONE
            return entries.find { it.budgetName == name }
        }

        fun fromNameOrDefault(name: String?): Budget {
            return name?.let { fromName(it) } ?: MAIN
        }
    }
}
