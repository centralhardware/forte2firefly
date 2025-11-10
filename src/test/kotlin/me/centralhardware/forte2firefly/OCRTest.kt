package me.centralhardware.forte2firefly

import me.centralhardware.forte2firefly.service.OCRService
import me.centralhardware.forte2firefly.service.TransactionParser
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OCRTest {

    @Test
    fun `test OCR recognition with sample photo`() {
        // Загружаем тестовое фото
        val photoFile = File("photo_2025-11-10_02-18-19.jpg")
        
        if (!photoFile.exists()) {
            println("⚠️ Test photo not found at: ${photoFile.absolutePath}")
            println("Please make sure photo_2025-11-10_02-18-19.jpg exists in project root")
            return
        }

        println("📷 Loading photo: ${photoFile.absolutePath}")
        println("Photo size: ${photoFile.length()} bytes")

        // Читаем фото как байты
        val photoBytes = photoFile.readBytes()

        // Создаем OCR сервис
        println("\n🔧 Initializing OCR Service...")
        val ocrService = try {
            OCRService()
        } catch (e: Exception) {
            println("❌ Failed to initialize OCR Service")
            println("Error: ${e.message}")
            println("\nMake sure Tesseract is installed:")
            println("  macOS: brew install tesseract")
            println("  Ubuntu: sudo apt-get install tesseract-ocr")
            println("  Check: tesseract --version")
            throw e
        }

        println("✅ OCR Service initialized successfully")

        // Выполняем распознавание
        println("\n🔍 Starting OCR recognition...")
        val recognizedText = ocrService.recognizeText(photoBytes)

        // Выводим результат
        println("\n📝 OCR Result:")
        println("=" * 50)
        println(recognizedText)
        println("=" * 50)
        println("\nText length: ${recognizedText.length} characters")
        println("Lines: ${recognizedText.lines().size}")

        // Проверяем что текст не пустой
        assertTrue(recognizedText.isNotBlank(), "OCR should recognize some text")

        // Парсим транзакцию
        println("\n🔄 Parsing transaction...")
        val parser = TransactionParser()
        val transaction = parser.parseTransaction(recognizedText)

        if (transaction != null) {
            println("✅ Transaction parsed successfully:")
            println("  Description: ${transaction.description}")
            println("  Amount: ${transaction.amount} ${transaction.currencySymbol}")
            println("  Date: ${transaction.dateTime}")
            println("  From: ${transaction.from}")
            println("  Transaction Number: ${transaction.transactionNumber}")
            println("  Transaction Amount: ${transaction.transactionAmount}")

            // Определяем валюту
            val detectedCurrency = parser.detectCurrency(transaction.currencySymbol)
            println("  Detected Currency: $detectedCurrency")

            // Конвертируем дату
            val fireflyDate = parser.convertToFireflyDate(transaction.dateTime)
            println("  Firefly Date: $fireflyDate")

            assertNotNull(transaction.description)
            assertNotNull(transaction.amount)
        } else {
            println("⚠️ Warning: Could not parse transaction from recognized text")
            println("This might be due to OCR accuracy issues or unexpected text format")
        }
    }

    @Test
    fun `test OCR with preprocessing`() {
        val photoFile = File("photo_2025-11-10_02-18-19.jpg")

        if (!photoFile.exists()) {
            println("⚠️ Test photo not found, skipping test")
            return
        }

        println("📷 Testing OCR with preprocessing...")
        val photoBytes = photoFile.readBytes()

        val ocrService = try {
            OCRService()
        } catch (e: Exception) {
            println("❌ Tesseract not available, skipping test")
            return
        }

        println("\n🔍 Running OCR with preprocessing...")
        val recognizedText = ocrService.recognizeTextWithPreprocessing(photoBytes)

        println("\n📝 OCR Result (with preprocessing):")
        println("=" * 50)
        println(recognizedText)
        println("=" * 50)

        assertTrue(recognizedText.isNotBlank(), "OCR with preprocessing should recognize some text")
    }
}

// Вспомогательная функция для повторения строки
private operator fun String.times(n: Int): String = this.repeat(n)
