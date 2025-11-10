package me.centralhardware.forte2firefly

import me.centralhardware.forte2firefly.service.OCRService
import me.centralhardware.forte2firefly.service.TransactionParser
import java.io.File

/**
 * Простая программа для тестирования OCR распознавания
 */
fun main() {
    println("=== Forte2Firefly OCR Test ===\n")
    
    // Загружаем тестовое фото
    val photoFile = File("photo_2025-11-10_02-18-19.jpg")
    
    if (!photoFile.exists()) {
        println("❌ Фото не найдено: ${photoFile.absolutePath}")
        println("Пожалуйста, убедитесь что photo_2025-11-10_02-18-19.jpg находится в корне проекта")
        return
    }

    println("📷 Загрузка фото: ${photoFile.name}")
    println("   Размер: ${photoFile.length()} байт\n")

    // Читаем фото как байты
    val photoBytes = photoFile.readBytes()

    // Создаем OCR сервис
    println("🔧 Инициализация Tesseract OCR...")
    val ocrService = try {
        OCRService()
    } catch (e: Exception) {
        println("❌ Ошибка инициализации OCR:")
        println("   ${e.message}\n")
        println("Убедитесь что Tesseract установлен:")
        println("  • macOS: brew install tesseract")
        println("  • Ubuntu: sudo apt-get install tesseract-ocr")
        println("  • Проверка: tesseract --version")
        return
    }
    println("✅ OCR инициализирован\n")

    // Выполняем распознавание с предобработкой (улучшенное качество)
    println("🔍 Распознавание текста с предобработкой изображения...")
    val startTime = System.currentTimeMillis()
    val recognizedText = ocrService.recognizeTextWithPreprocessing(photoBytes)
    val elapsedTime = System.currentTimeMillis() - startTime

    println("✅ Распознавание завершено за ${elapsedTime}ms")
    println("   (использовано: upscaling 1.5x, grayscale, contrast 1.3x, threshold)\n")

    // Выводим результат
    println("=" * 60)
    println("📝 РАСПОЗНАННЫЙ ТЕКСТ:")
    println("=" * 60)
    println(recognizedText)
    println("=" * 60)
    println()
    println("📊 Статистика:")
    println("   Длина текста: ${recognizedText.length} символов")
    println("   Строк: ${recognizedText.lines().size}")
    println()

    // Парсим транзакцию
    println("🔄 Парсинг транзакции...")
    val parser = TransactionParser()
    val transaction = parser.parseTransaction(recognizedText)

    if (transaction != null) {
        println("✅ Транзакция успешно распознана:\n")
        
        println("┌─── ДЕТАЛИ ТРАНЗАКЦИИ ───────────────────────────┐")
        println("│")
        println("│ 📝 Описание:           ${transaction.description}")
        println("│ 💰 Сумма:              ${transaction.amount} ${transaction.currencySymbol}")
        println("│ 📅 Дата и время:       ${transaction.dateTime}")
        println("│ 🏦 Источник:           ${transaction.from}")
        println("│ 🔢 Номер транзакции:   ${transaction.transactionNumber}")
        println("│ 💵 Transaction amount: ${transaction.transactionAmount}")
        println("│")
        
        // Определяем валюту
        val detectedCurrency = parser.detectCurrency(transaction.currencySymbol)
        println("│ 💱 Определенная валюта: $detectedCurrency")
        
        // Конвертируем дату
        val fireflyDate = parser.convertToFireflyDate(transaction.dateTime)
        println("│ 📆 Дата для Firefly:   $fireflyDate")
        println("│")
        println("└──────────────────────────────────────────────────┘")
        
        println("\n✅ Все данные распознаны корректно!")
        println("   Транзакция готова к отправке в Firefly III")
        
    } else {
        println("⚠️  Не удалось распознать транзакцию")
        println("    Возможные причины:")
        println("    • Качество OCR недостаточно хорошее")
        println("    • Формат текста не соответствует ожидаемому")
        println("    • Попробуйте использовать более четкое фото")
    }
    
    println("\n=== Тест завершен ===")
}

// Вспомогательная функция для повторения строки
private operator fun String.times(n: Int): String = this.repeat(n)
