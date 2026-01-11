package me.centralhardware.forte2firefly

import kotlinx.coroutines.test.runTest
import me.centralhardware.forte2firefly.service.ConversionResult
import me.centralhardware.forte2firefly.service.ExchangeRateService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ExchangeRateServiceTest {

    @Test
    fun `test USD returns NoConversionNeeded`() = runTest {
        val result = ExchangeRateService.convertToUSD("100.00", "USD")
        assertTrue(result is ConversionResult.NoConversionNeeded)
    }

    @Test
    fun `test GBP returns NoConversionNeeded`() = runTest {
        val result = ExchangeRateService.convertToUSD("100.00", "GBP")
        assertTrue(result is ConversionResult.NoConversionNeeded)
    }

    @Test
    fun `test JPY returns NoConversionNeeded`() = runTest {
        val result = ExchangeRateService.convertToUSD("10000", "JPY")
        assertTrue(result is ConversionResult.NoConversionNeeded)
    }

    @Test
    fun `test formatAmount with 2 decimals`() {
        assertEquals("48.30", ExchangeRateService.formatAmount("48.30"))
    }

    @Test
    fun `test formatAmount rounds to 2 decimals`() {
        assertEquals("53.13", ExchangeRateService.formatAmount("53.12999"))
    }

    @Test
    fun `test formatAmount rounds up correctly`() {
        assertEquals("53.14", ExchangeRateService.formatAmount("53.135"))
    }

    @Test
    fun `test formatAmount handles integer`() {
        assertEquals("100.00", ExchangeRateService.formatAmount("100"))
    }

    @Test
    fun `test formatAmount handles invalid input`() {
        assertEquals("invalid", ExchangeRateService.formatAmount("invalid"))
    }

    @Test
    fun `test formatAmount handles empty string`() {
        assertEquals("", ExchangeRateService.formatAmount(""))
    }
}
