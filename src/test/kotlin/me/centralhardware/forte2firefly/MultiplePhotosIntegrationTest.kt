package me.centralhardware.forte2firefly

import kotlinx.coroutines.runBlocking
import me.centralhardware.forte2firefly.service.CurrencyService
import me.centralhardware.forte2firefly.service.OCRService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.time.ZoneId

class   MultiplePhotosIntegrationTest {

    private fun testPhoto(
        photoFileName: String,
        expectedDescription: String,
        expectedAmount: String,
        expectedCurrencySymbol: String,
        expectedForeignAmount: String?,
        expectedYear: Int,
        expectedMonth: Int,
        expectedDay: Int,
        expectedHour: Int,
        expectedMinute: Int,
        expectedSecond: Int,
        expectedCardLast4: String,
        expectedTransactionNumber: String,
        expectedMccCode: String? = null
    ) = runBlocking {
        val photoStream = javaClass.classLoader.getResourceAsStream(photoFileName)
            ?: this::class.java.getResourceAsStream("/$photoFileName")

        assertNotNull(photoStream, "Photo $photoFileName should exist in resources")

        val photoBytes = photoStream!!.use { it.readBytes() }

        val transaction = try {
            OCRService.extractAllFields(photoBytes, true)
        } catch (e: IllegalStateException) {
            println("⚠️ Skipping test: Tesseract OCR not available")
            Assumptions.assumeTrue(false, "Tesseract OCR not available")
            return@runBlocking
        }

        assertNotNull(transaction, "Transaction should be extracted from $photoFileName")

        assertEquals(expectedDescription, transaction!!.description,
            "[$photoFileName] Description should match")

        assertEquals(expectedAmount, transaction.amount,
            "[$photoFileName] Amount should match")

        assertEquals(expectedForeignAmount, transaction.transactionAmount,
            "[$photoFileName] Foreign amount should match")

        assertEquals(expectedCurrencySymbol, transaction.currencySymbol,
            "[$photoFileName] Currency symbol should match")

        assertEquals(expectedYear, transaction.dateTime.year,
            "[$photoFileName] Year should match")
        assertEquals(expectedMonth, transaction.dateTime.monthValue,
            "[$photoFileName] Month should match")
        assertEquals(expectedDay, transaction.dateTime.dayOfMonth,
            "[$photoFileName] Day should match")
        assertEquals(expectedHour, transaction.dateTime.hour,
            "[$photoFileName] Hour should match")
        assertEquals(expectedMinute, transaction.dateTime.minute,
            "[$photoFileName] Minute should match")
        assertEquals(expectedSecond, transaction.dateTime.second,
            "[$photoFileName] Second should match")
        assertEquals(ZoneId.of("Asia/Almaty"), transaction.dateTime.zone,
            "[$photoFileName] Timezone should be Asia/Almaty")

        assertTrue(transaction.from.contains(expectedCardLast4),
            "[$photoFileName] Card should contain last 4 digits $expectedCardLast4")

        assertEquals(expectedTransactionNumber, transaction.transactionNumber,
            "[$photoFileName] Transaction number should match")

        if (expectedMccCode != null) {
            assertEquals(expectedMccCode, transaction.mccCode,
                "[$photoFileName] MCC code should match")
        }

        val expectedCurrency = when (expectedCurrencySymbol) {
            "$" -> "USD"
            "€" -> "EUR"
            "T", "₸" -> "KZT"
            else -> "USD"
        }
        val detectedCurrency = CurrencyService.detectCurrency(transaction.currencySymbol)
        assertEquals(expectedCurrency, detectedCurrency,
            "[$photoFileName] Currency should be detected as $expectedCurrency")

        println("✅ [$photoFileName] All assertions passed!")
    }

    @Test
    fun `test nsk_grocer_usd_18_29`() = testPhoto(
        photoFileName = "nsk_grocer_usd_18_29.jpg",
        expectedDescription = "NSK GROCER- QCM",
        expectedAmount = "18.29",
        expectedForeignAmount = "75.5",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 11,
        expectedDay = 9,
        expectedHour = 15,
        expectedMinute = 37,
        expectedSecond = 39,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12165085404"
    )

    @Test
    fun `test coffee_103_usd_13_99`() = testPhoto(
        photoFileName = "coffee_103_usd_13_99.jpg",
        expectedDescription = "103 COFFEE-CHOWKIT",
        expectedAmount = "13.99",
        expectedForeignAmount = "56.8",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 12,
        expectedDay = 6,
        expectedHour = 14,
        expectedMinute = 8,
        expectedSecond = 17,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12444824085",
        expectedMccCode = "5812"
    )

    @Test
    fun `test grab_rides_usd_3_10`() = testPhoto(
        photoFileName = "grab_rides_usd_3_10.jpg",
        expectedDescription = "GRAB RIDES-EC",
        expectedAmount = "3.10",
        expectedForeignAmount = "12.57",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 12,
        expectedDay = 6,
        expectedHour = 11,
        expectedMinute = 48,
        expectedSecond = 40,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12443316864",
        expectedMccCode = "4121",
    )

    @Test
    fun `test lazada_usd_35_28`() = testPhoto(
        photoFileName = "lazada_usd_35_28.jpg",
        expectedDescription = "Lazada",
        expectedAmount = "35.28",
        expectedForeignAmount = "143.5",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 12,
        expectedDay = 5,
        expectedHour = 9,
        expectedMinute = 43,
        expectedSecond = 23,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12429595311",
        expectedMccCode = "5310",
    )

    @Test
    fun `test genki_world_eur_48_30`() = testPhoto(
        photoFileName = "genki_world_eur_48_30.jpg",
        expectedDescription = "WWW.GENKI.WORLD",
        expectedAmount = "48.30",
        expectedForeignAmount = null,
        expectedCurrencySymbol = "€",
        expectedYear = 2025,
        expectedMonth = 11,
        expectedDay = 29,
        expectedHour = 4,
        expectedMinute = 0,
        expectedSecond = 47,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12364607070",
        expectedMccCode = "6300"
    )

    @Test
    fun `test grab_rides_usd_0_48`() = testPhoto(
        photoFileName = "grab_rides_usd_0_48.jpg",
        expectedDescription = "GRAB RIDES-EC",
        expectedAmount = "0.48",
        expectedForeignAmount = "2",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 11,
        expectedDay = 23,
        expectedHour = 13,
        expectedMinute = 0,
        expectedSecond = 12,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12305435457",
        expectedMccCode = "4121",
    )

    @Test
    fun `test lemsqzy_deskrest_usd_19_99`() = testPhoto(
        photoFileName = "lemsqzy_deskrest_usd_19_99.jpg",
        expectedDescription = "LEMSQZY* DESKREST",
        expectedAmount = "19.99",
        expectedForeignAmount = null,
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 11,
        expectedDay = 23,
        expectedHour = 7,
        expectedMinute = 56,
        expectedSecond = 36,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12303874302",
        expectedMccCode = "5734",
    )

    @Test
    fun `test xsolla_kzt_8021_60`() = testPhoto(
        photoFileName = "xsolla_kzt_8021_60.jpg",
        expectedDescription = "Xsolla *1001",
        expectedAmount = "8021.60",
        expectedForeignAmount = null,
        expectedCurrencySymbol = "T",
        expectedYear = 2025,
        expectedMonth = 11,
        expectedDay = 22,
        expectedHour = 23,
        expectedMinute = 7,
        expectedSecond = 18,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12303079205",
        expectedMccCode = "5816",
    )

    @Test
    fun `test watsons_usd_6_31`() = testPhoto(
        photoFileName = "watsons_usd_6_31.jpg",
        expectedDescription = "WATSON'S QUILL CITY (M602",
        expectedAmount = "6.31",
        expectedForeignAmount = "25.9",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 11,
        expectedDay = 21,
        expectedHour = 12,
        expectedMinute = 59,
        expectedSecond = 41,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12289073601",
        expectedMccCode = "5912"
    )

    @Test
    fun `test uber_pending_usd_13_48`() = testPhoto(
        photoFileName = "uber_pending_usd_13_48.png",
        expectedDescription = "UBR* PENDING.UBER.COM",
        expectedAmount = "13.48",
        expectedForeignAmount = "4128.47",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 12,
        expectedDay = 20,
        expectedHour = 22,
        expectedMinute = 7,
        expectedSecond = 8,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12601751386",
        expectedMccCode = "4121"
    )

    @Test
    fun `test kiri_kopi_usd_11_13`() = testPhoto(
        photoFileName = "kiri_kopi_usd_11_13.jpg",
        expectedDescription = "KIRI KOPI COLOMBO",
        expectedAmount = "11.13",
        expectedForeignAmount = "3410",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 12,
        expectedDay = 21,
        expectedHour = 20,
        expectedMinute = 29,
        expectedSecond = 50,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12608576722",
        expectedMccCode = "5812"
    )

    @Test
    fun `test bolt_usd_1_48`() = testPhoto(
        photoFileName = "bolt_usd_1_48.jpg",
        expectedDescription = "BOLT.EU/O/2512150631",
        expectedAmount = "1.48",
        expectedForeignAmount = "6",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 12,
        expectedDay = 15,
        expectedHour = 11,
        expectedMinute = 31,
        expectedSecond = 28,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12543609908",
        expectedMccCode = "4121"
    )

    @Test
    fun `test pasaraya_angkasa_usd_5_16`() = testPhoto(
        photoFileName = "pasaraya_angkasa_usd_5_16.jpg",
        expectedDescription = "PASARAYA ANGKASA",
        expectedAmount = "5.16",
        expectedForeignAmount = "20.65",
        expectedCurrencySymbol = "$",
        expectedYear = 2026,
        expectedMonth = 1,
        expectedDay = 7,
        expectedHour = 18,
        expectedMinute = 35,
        expectedSecond = 24,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12790559778",
        expectedMccCode = "5411"
    )

    @Test
    fun `test grab_ec_usd_7_10`() = testPhoto(
        photoFileName = "grab_ec_usd_7_10.jpg",
        expectedDescription = "GRAB-EC",
        expectedAmount = "7.10",
        expectedForeignAmount = "28.52",
        expectedCurrencySymbol = "$",
        expectedYear = 2026,
        expectedMonth = 1,
        expectedDay = 9,
        expectedHour = 7,
        expectedMinute = 55,
        expectedSecond = 17,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12803935825",
        expectedMccCode = "5499"
    )
}
