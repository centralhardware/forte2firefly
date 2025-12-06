package me.centralhardware.forte2firefly

import me.centralhardware.forte2firefly.service.TransactionParser
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

class TimezoneConversionTest {

    @Test
    fun `convert Almaty time to UTC plus 1 hour for Firefly`() {
        // Создаем ZonedDateTime для Алматы: 09 november 2025 15:37:39 (UTC+5)
        val almatyTime = ZonedDateTime.of(
            LocalDateTime.of(2025, 11, 9, 15, 37, 39),
            ZoneId.of("Asia/Almaty")
        )

        // UTC время: 15:37 - 5 часов = 10:37
        // Плюс 1 час для компенсации: 10:37 + 1 = 11:37
        val expectedTime = "2025-11-09T11:37:39"

        val result = TransactionParser.convertToFireflyDate(almatyTime)

        assertEquals(expectedTime, result,
            "Should convert to UTC and add 1 hour for Firefly adjustment")
    }

    @Test
    fun `convert Almaty time near midnight to UTC plus 1 hour`() {
        // 23:00 в Алматы - 5 часов = 18:00 UTC
        // Плюс 1 час: 18:00 + 1 = 19:00
        val almatyTime = ZonedDateTime.of(
            LocalDateTime.of(2025, 11, 9, 23, 0, 0),
            ZoneId.of("Asia/Almaty")
        )

        val expectedTime = "2025-11-09T19:00:00"

        val result = TransactionParser.convertToFireflyDate(almatyTime)

        assertEquals(expectedTime, result,
            "Should convert to UTC and add 1 hour")
    }

    @Test
    fun `convert Almaty time early morning to UTC plus 1 hour with day change`() {
        // 01:00 в Алматы - 5 часов = 20:00 предыдущего дня UTC
        // Плюс 1 час: 20:00 + 1 = 21:00
        val almatyTime = ZonedDateTime.of(
            LocalDateTime.of(2025, 11, 9, 1, 0, 0),
            ZoneId.of("Asia/Almaty")
        )

        val expectedTime = "2025-11-08T21:00:00"

        val result = TransactionParser.convertToFireflyDate(almatyTime)

        assertEquals(expectedTime, result,
            "Should handle day change and add 1 hour to UTC")
    }

    @Test
    fun `convert Almaty time at noon to UTC plus 1 hour`() {
        // 12:00 в Алматы - 5 часов = 07:00 UTC
        // Плюс 1 час: 07:00 + 1 = 08:00
        val almatyTime = ZonedDateTime.of(
            LocalDateTime.of(2024, 12, 15, 12, 0, 0),
            ZoneId.of("Asia/Almaty")
        )

        val expectedTime = "2024-12-15T08:00:00"

        val result = TransactionParser.convertToFireflyDate(almatyTime)

        assertEquals(expectedTime, result,
            "Should convert to UTC and add 1 hour")
    }
}
