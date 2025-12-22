package me.centralhardware.forte2firefly

import me.centralhardware.forte2firefly.model.Budget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BudgetTest {

    @Test
    fun `NONE budget has null budgetName`() {
        assertNull(Budget.NONE.budgetName)
    }

    @Test
    fun `NONE budget has correct emoji`() {
        assertEquals("🚫", Budget.NONE.emoji)
    }

    @Test
    fun `fromName returns NONE for none string`() {
        val budget = Budget.fromName("none")
        assertEquals(Budget.NONE, budget)
    }

    @Test
    fun `fromName returns MAIN for main string`() {
        val budget = Budget.fromName("main")
        assertEquals(Budget.MAIN, budget)
    }

    @Test
    fun `fromName returns null for unknown budget`() {
        val budget = Budget.fromName("unknown")
        assertNull(budget)
    }

    @Test
    fun `cycling includes NONE budget`() {
        assertEquals(Budget.STUDY, Budget.MAIN.getNext())
        assertEquals(Budget.SUBSCRIPTIONS, Budget.STUDY.getNext())
        assertEquals(Budget.NONE, Budget.SUBSCRIPTIONS.getNext())
        assertEquals(Budget.MAIN, Budget.NONE.getNext())
    }

    @Test
    fun `fromNameOrDefault returns MAIN for null`() {
        val budget = Budget.fromNameOrDefault(null)
        assertEquals(Budget.MAIN, budget)
    }

    @Test
    fun `fromNameOrDefault returns NONE for none string`() {
        val budget = Budget.fromNameOrDefault("none")
        assertEquals(Budget.NONE, budget)
    }
}
