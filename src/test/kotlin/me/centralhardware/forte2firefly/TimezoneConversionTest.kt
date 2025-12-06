package me.centralhardware.forte2firefly

import me.centralhardware.forte2firefly.service.TransactionParser
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

class TimezoneConversionTest {

    @Test
    fun `convert Almaty time to UTC`() {
        // Создаем ZonedDateTime для Алматы: 09 november 2025 15:37:39 (UTC+5)
        val almatyTime = ZonedDateTime.of(
            LocalDateTime.of(2025, 11, 9, 15, 37, 39),
            ZoneId.of("Asia/Almaty")
        )

        // Ожидаемое время в UTC: -5 часов
        val expectedUtcTime = "2025-11-09T10:37:39"

        val result = TransactionParser.convertToFireflyDate(almatyTime)

        assertEquals(expectedUtcTime, result,
            "Should convert from Almaty (UTC+5) to UTC (-5 hours)")
    }

    @Test
    fun `convert Almaty time near midnight to UTC`() {
        // 23:00 в Алматы должно стать 18:00 того же дня в UTC
        val almatyTime = ZonedDateTime.of(
            LocalDateTime.of(2025, 11, 9, 23, 0, 0),
            ZoneId.of("Asia/Almaty")
        )

        // -5 часов = 18:00 того же дня
        val expectedUtcTime = "2025-11-09T18:00:00"

        val result = TransactionParser.convertToFireflyDate(almatyTime)

        assertEquals(expectedUtcTime, result,
            "Should correctly subtract 5 hours for late evening time")
    }

    @Test
    fun `convert Almaty time early morning to UTC with day change`() {
        // 01:00 в Алматы = 20:00 предыдущего дня в UTC
        val almatyTime = ZonedDateTime.of(
            LocalDateTime.of(2025, 11, 9, 1, 0, 0),
            ZoneId.of("Asia/Almaty")
        )

        // -5 часов переводит на предыдущий день
        val expectedUtcTime = "2025-11-08T20:00:00"

        val result = TransactionParser.convertToFireflyDate(almatyTime)

        assertEquals(expectedUtcTime, result,
            "Should handle day change when converting early morning to UTC")
    }

    @Test
    fun `convert Almaty time at noon to UTC`() {
        // 12:00 в Алматы = 07:00 в UTC
        val almatyTime = ZonedDateTime.of(
            LocalDateTime.of(2024, 12, 15, 12, 0, 0),
            ZoneId.of("Asia/Almaty")
        )

        val expectedUtcTime = "2024-12-15T07:00:00"

        val result = TransactionParser.convertToFireflyDate(almatyTime)

        assertEquals(expectedUtcTime, result,
            "Should correctly subtract 5 hours for noon time")
    }
}
