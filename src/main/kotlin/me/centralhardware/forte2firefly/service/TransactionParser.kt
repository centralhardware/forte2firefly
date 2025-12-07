package me.centralhardware.forte2firefly.service

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.debug
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object TransactionParser {

    fun convertToFireflyDate(zonedDateTime: ZonedDateTime): String {
        // Конвертируем из Asia/Almaty в UTC
        val utcZone = ZoneId.of("UTC")
        val utcTime = zonedDateTime.withZoneSameInstant(utcZone)
        
        // Добавляем 1 час, так как Firefly показывает на 1 час раньше
        val adjustedTime = utcTime.plusHours(1)
        
        val result = adjustedTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        KSLog.debug("Converted date: ${zonedDateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} (${zonedDateTime.zone}) -> ${utcTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)} (UTC) -> $result (UTC+1h for Firefly)")
        return result
    }

}
