package me.centralhardware.forte2firefly

import kotlinx.coroutines.runBlocking
import me.centralhardware.forte2firefly.service.CountryToCurrencyMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CountryToCurrencyMapperTest {

    @BeforeEach
    fun setup() {
        // Очищаем кэш перед каждым тестом
        CountryToCurrencyMapper.clearCache()
    }

    @AfterEach
    fun cleanup() {
        CountryToCurrencyMapper.clearCache()
    }

    @Test
    fun `USA returns USD from REST Countries API`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("United States")
        assertEquals("USD", currency)
    }

    @Test
    fun `Russia returns RUB from REST Countries API`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("Russia")
        assertEquals("RUB", currency)
    }

    @Test
    fun `Malaysia returns MYR from REST Countries API`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("Malaysia")
        assertEquals("MYR", currency)
    }

    @Test
    fun `Kazakhstan returns KZT from REST Countries API`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("Kazakhstan")
        assertEquals("KZT", currency)
    }

    @Test
    fun `UK returns GBP from REST Countries API`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("United Kingdom")
        assertEquals("GBP", currency)
    }

    @Test
    fun `Germany returns EUR from REST Countries API`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("Germany")
        assertEquals("EUR", currency)
    }

    @Test
    fun `Japan returns JPY from REST Countries API`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("Japan")
        assertEquals("JPY", currency)
    }

    @Test
    fun `Singapore returns SGD from REST Countries API`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("Singapore")
        assertEquals("SGD", currency)
    }

    @Test
    fun `Thailand returns THB from REST Countries API`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("Thailand")
        assertEquals("THB", currency)
    }

    @Test
    fun `null country returns null`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry(null)
        assertNull(currency)
    }

    @Test
    fun `empty country returns null`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("")
        assertNull(currency)
    }

    @Test
    fun `cache works correctly`() = runBlocking {
        assertEquals(0, CountryToCurrencyMapper.getCacheSize())

        // Первый запрос
        val currency1 = CountryToCurrencyMapper.getCurrencyByCountry("Japan")
        assertEquals("JPY", currency1)
        assertEquals(1, CountryToCurrencyMapper.getCacheSize())

        // Второй запрос (из кэша)
        val currency2 = CountryToCurrencyMapper.getCurrencyByCountry("Japan")
        assertEquals("JPY", currency2)
        assertEquals(1, CountryToCurrencyMapper.getCacheSize())

        // Другая страна
        val currency3 = CountryToCurrencyMapper.getCurrencyByCountry("Germany")
        assertEquals("EUR", currency3)
        assertEquals(2, CountryToCurrencyMapper.getCacheSize())
    }

    @Test
    fun `clearCache removes all cached entries`() = runBlocking {
        CountryToCurrencyMapper.getCurrencyByCountry("Japan")
        CountryToCurrencyMapper.getCurrencyByCountry("Germany")
        assertEquals(2, CountryToCurrencyMapper.getCacheSize())

        CountryToCurrencyMapper.clearCache()
        assertEquals(0, CountryToCurrencyMapper.getCacheSize())
    }

    @Test
    fun `Switzerland returns CHF from REST Countries API`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("Switzerland")
        assertEquals("CHF", currency)
    }

    @Test
    fun `Australia returns AUD from REST Countries API`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("Australia")
        assertEquals("AUD", currency)
    }

    @Test
    fun `Canada returns CAD from REST Countries API`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("Canada")
        assertEquals("CAD", currency)
    }

    @Test
    fun `API returns valid currency code`() = runBlocking {
        val currency = CountryToCurrencyMapper.getCurrencyByCountry("Poland")
        assertNotNull(currency)
        assertEquals(3, currency?.length) // ISO 4217 коды всегда 3 символа
    }
}
