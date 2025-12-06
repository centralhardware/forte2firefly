package me.centralhardware.forte2firefly

import kotlinx.coroutines.runBlocking
import me.centralhardware.forte2firefly.service.OCRService
import me.centralhardware.forte2firefly.service.TransactionParser
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultiplePhotosIntegrationTest {

    private fun testPhoto(
        photoFileName: String,
        expectedDescription: String,
        expectedAmount: String,
        expectedCurrencySymbol: String,
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

        val photoBytes = photoStream.use { it.readBytes() }

        val recognizedText = try {
            OCRService.recognizeText(photoBytes)
        } catch (e: IllegalStateException) {
            println("⚠️ Skipping test: Tesseract OCR not available")
            Assumptions.assumeTrue(false, "Tesseract OCR not available")
            return@runBlocking
        }

        println("\n📝 OCR recognized text for $photoFileName:")
        println("=".repeat(50))
        println(recognizedText)
        println("=".repeat(50))

        val transaction = TransactionParser.parseTransaction(recognizedText)

        assertNotNull(transaction, "Transaction should be parsed from $photoFileName")

        assertEquals(expectedDescription, transaction.description,
            "[$photoFileName] Description should match")

        assertEquals(expectedAmount, transaction.amount,
            "[$photoFileName] Amount should match")

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
        val detectedCurrency = TransactionParser.detectCurrency(transaction.currencySymbol)
        assertEquals(expectedCurrency, detectedCurrency,
            "[$photoFileName] Currency should be detected as $expectedCurrency")

        println("✅ [$photoFileName] All assertions passed!")
    }

    @Test
    fun `test photo_2025-11-10_02-18-19`() = testPhoto(
        photoFileName = "photo_2025-11-10_02-18-19.jpg",
        expectedDescription = "NSK GROCER- QCM",
        expectedAmount = "18.29",
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
    fun `test photo_2025-12-06_20-02-28`() = testPhoto(
        photoFileName = "photo_2025-12-06_20-02-28.jpg",
        expectedDescription = "103 COFFEE-CHOWKIT",
        expectedAmount = "13.99",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 12,
        expectedDay = 6,
        expectedHour = 14,
        expectedMinute = 8,
        expectedSecond = 17,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12444824085"
    )

    @Test
    fun `test photo_2025-12-06_20-02-47`() = testPhoto(
        photoFileName = "photo_2025-12-06_20-02-47.jpg",
        expectedDescription = "GRAB RIDES-EC",
        expectedAmount = "3.10",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 12,
        expectedDay = 6,
        expectedHour = 11,
        expectedMinute = 48,
        expectedSecond = 40,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12443316864"
    )

    @Test
    fun `test photo_2025-12-06_20-03-10`() = testPhoto(
        photoFileName = "photo_2025-12-06_20-03-10.jpg",
        expectedDescription = "Lazada",
        expectedAmount = "35.28",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 12,
        expectedDay = 5,
        expectedHour = 9,
        expectedMinute = 43,
        expectedSecond = 23,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12429595311"
    )

    @Test
    fun `test photo_2025-12-06_20-03-46`() = testPhoto(
        photoFileName = "photo_2025-12-06_20-03-46.jpg",
        expectedDescription = "WWW.GENKI.WORLD",
        expectedAmount = "48.30",
        expectedCurrencySymbol = "€",
        expectedYear = 2025,
        expectedMonth = 11,
        expectedDay = 29,
        expectedHour = 4,
        expectedMinute = 0,
        expectedSecond = 47,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12364607070"
    )

    @Test
    fun `test photo_2025-12-06_20-04-30`() = testPhoto(
        photoFileName = "photo_2025-12-06_20-04-30.jpg",
        expectedDescription = "GRAB RIDES-EC",
        expectedAmount = "0.48",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 11,
        expectedDay = 23,
        expectedHour = 13,
        expectedMinute = 0,
        expectedSecond = 12,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12305435457"
    )

    @Test
    fun `test photo_2025-12-06_20-04-46`() = testPhoto(
        photoFileName = "photo_2025-12-06_20-04-46.jpg",
        expectedDescription = "LEMSQZY* DESKREST",
        expectedAmount = "19.99",
        expectedCurrencySymbol = "$",
        expectedYear = 2025,
        expectedMonth = 11,
        expectedDay = 23,
        expectedHour = 7,
        expectedMinute = 56,
        expectedSecond = 36,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12303874302"
    )

    @Test
    fun `test photo_2025-12-06_20-04-50`() = testPhoto(
        photoFileName = "photo_2025-12-06_20-04-50.jpg",
        expectedDescription = "Xsolla *1001",
        expectedAmount = "8021.60",
        expectedCurrencySymbol = "T",
        expectedYear = 2025,
        expectedMonth = 11,
        expectedDay = 22,
        expectedHour = 23,
        expectedMinute = 7,
        expectedSecond = 18,
        expectedCardLast4 = "1293",
        expectedTransactionNumber = "12303079205"
    )

    @Test
    fun `test photo_2025-12-06_20-05-13`() = testPhoto(
        photoFileName = "photo_2025-12-06_20-05-13.jpg",
        expectedDescription = "WATSON'S QUILL CITY (M602",
        expectedAmount = "6.31",
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
}
