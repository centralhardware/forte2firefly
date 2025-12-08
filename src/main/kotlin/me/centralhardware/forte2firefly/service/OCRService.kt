package me.centralhardware.forte2firefly.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.centralhardware.forte2firefly.Config
import me.centralhardware.forte2firefly.model.ForteTransaction
import net.sourceforge.tess4j.Tesseract
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.debug
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info
import dev.inmo.kslog.common.warning
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZonedDateTime
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min


object OCRService {

    // Общий Tesseract для fallback
    private val tesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "General",
            pageSegMode = 11 // PSM 11 = Sparse text
        )
    }

    // Tesseract для merchant name (текст с заглавными буквами)
    private val merchantTesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "Merchant",
            pageSegMode = 11 // PSM 11 = Sparse text (find as much text as possible)
        )
    }

    // Tesseract для amount (цифры + символы валют)
    private val amountTesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "Amount",
            pageSegMode = 7 // PSM 7 = Single text line
        )
    }

    // Tesseract для datetime (текст с датой и временем)
    private val datetimeTesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "DateTime",
            pageSegMode = 7 // PSM 7 = Single text line
        )
    }

    // Tesseract для card number (текст с номером карты)
    private val cardTesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "Card",
            pageSegMode = 7 // PSM 7 = Single text line
        )
    }

    // Tesseract для transaction number (только цифры)
    private val transactionNumberTesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "Transaction Number",
            pageSegMode = 7, // PSM 7 = Single text line
            whitelist = "0123456789",
            additionalVariables = mapOf("classify_bln_numeric_mode" to "1")
        )
    }

    // Tesseract для MCC code (4 цифры)
    private val mccTesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "MCC Code",
            pageSegMode = 10, // PSM 10 = Single character
            language = "", // No language for numeric-only
            whitelist = ",.0123456789",
            additionalVariables = mapOf("classify_bln_numeric_mode" to "1")
        )
    }

    
    /**
     * Комплексное извлечение всех полей транзакции с использованием region-based OCR
     */
    suspend fun extractAllFields(photoBytes: ByteArray, debugMode: Boolean = false): ForteTransaction? = withContext(Dispatchers.IO) {
        KSLog.info("Starting comprehensive region-based OCR extraction")
        
        try {
            // Извлекаем все поля параллельно для ускорения
            val merchantName = recognizeMerchantName(photoBytes, debugMode)
            val amount = recognizeAmount(photoBytes, debugMode)
            val dateTime = recognizeDateTime(photoBytes, debugMode)
            val cardNumber = recognizeCardNumber(photoBytes, debugMode)
            val transactionNumber = recognizeTransactionNumber(photoBytes, debugMode)
            val foreignAmount = recognizeForeignAmount(photoBytes, debugMode)
            
            // Определяем MCC код
            // Сначала пытаемся распознать MCC напрямую из своего региона
            val mccFromRegion = recognizeMccCode(photoBytes, foreignAmount != null && foreignAmount.isNotEmpty(), debugMode)
            
            val mccCode = if (mccFromRegion != null && mccFromRegion.matches(Regex("^\\d{4}$"))) {
                // Если нашли MCC в своем регионе, используем его
                KSLog.info("MCC code found in dedicated region: '$mccFromRegion'")
                mccFromRegion
            } else if (foreignAmount != null && foreignAmount.matches(Regex("^\\d{4}$"))) {
                // Fallback: если foreign amount состоит из 4 цифр, используем его
                KSLog.info("Foreign amount '$foreignAmount' is 4-digit code, using as MCC fallback")
                foreignAmount
            } else if (foreignAmount != null && foreignAmount.matches(Regex("^\\d{5,}$"))) {
                // Если 5+ цифр, берём первые 4 как MCC (OCR часто добавляет лишние цифры в конце)
                val firstFour = foreignAmount.take(4)
                KSLog.info("Foreign amount '$foreignAmount' has 5+ digits, taking first 4 as MCC: '$firstFour'")
                firstFour
            } else {
                null
            }
            
            // Если foreign amount был MCC кодом, очищаем его
            val actualForeignAmount = if (foreignAmount != null && foreignAmount.matches(Regex("^\\d{4,}$"))) {
                null
            } else {
                foreignAmount
            }
            
            // Валидация обязательных полей
            if (merchantName == null) {
                KSLog.warning("Merchant name not found")
                return@withContext null
            }
            
            if (amount == null) {
                KSLog.warning("Amount not found")
                return@withContext null
            }
            
            if (dateTime == null) {
                KSLog.warning("DateTime not found")
                return@withContext null
            }
            
            if (cardNumber == null) {
                KSLog.warning("Card number not found")
                return@withContext null
            }
            
            if (transactionNumber == null) {
                KSLog.warning("Transaction number not found")
                return@withContext null
            }
            
            // Парсим дату и время
            val parsedDateTime = parseForteDateTime(dateTime)
            if (parsedDateTime == null) {
                KSLog.warning("Could not parse datetime: $dateTime")
                return@withContext null
            }
            
            val (amountValue, currencySymbol) = amount
            
            val transaction = ForteTransaction(
                description = merchantName,
                amount = amountValue.removePrefix("-"),
                currencySymbol = currencySymbol,
                dateTime = parsedDateTime,
                from = cardNumber,
                transactionNumber = transactionNumber,
                transactionAmount = actualForeignAmount,
                mccCode = mccCode
            )
            
            KSLog.info("Successfully extracted transaction: $transaction")
            transaction
        } catch (e: Exception) {
            KSLog.error("Error during comprehensive OCR extraction", e)
            null
        }
    }
    
    /**
     * Парсит дату и время из строки, распознанной OCR
     */
    private fun parseForteDateTime(forteDateTime: String): ZonedDateTime? {
        return try {
            // Заменяем OCR артефакты
            val cleanedDate = forteDateTime
                .replace("'s", "")
                .replace(Regex("^O"), "0")
                .replace(Regex("^0+(\\d{2})"), "$1")
                .trim()

            val inputFormatter = java.time.format.DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("dd MMMM yyyy HH:mm:ss")
                .toFormatter(java.util.Locale.ENGLISH)

            val localDateTime = java.time.LocalDateTime.parse(cleanedDate, inputFormatter)
            val almatyZone = java.time.ZoneId.of("Asia/Almaty")
            java.time.ZonedDateTime.of(localDateTime, almatyZone)
        } catch (e: Exception) {
            KSLog.error("Error parsing date: '$forteDateTime'", e)
            null
        }
    }
    /**
     * Определяет код валюты по символу
     */
    fun detectCurrency(currencySymbol: String): String {
        return when (currencySymbol) {
            "$" -> "USD"
            "€" -> "EUR"
            "£" -> "GBP"
            "¥" -> "JPY"
            "₽" -> "RUB"
            "₸", "T" -> "KZT"
            "RM" -> "MYR"
            else -> {
                KSLog.warning("Unknown currency symbol: $currencySymbol, defaulting to USD")
                "USD"
            }
        }
    }

    suspend fun recognizeText(photoBytes: ByteArray): String = withContext(Dispatchers.IO) {
        KSLog.info("Starting OCR with preprocessing for image (${photoBytes.size} bytes)")

        val inputStream = ByteArrayInputStream(photoBytes)
        val originalImage: BufferedImage = ImageIO.read(inputStream)
            ?: throw IllegalArgumentException("Could not read image from bytes")

        // Применяем preprocessing
        val preprocessedImage = ImagePreprocessor.preprocessImage(originalImage)

        val result = tesseract.doOCR(preprocessedImage)

        KSLog.info("OCR with preprocessing completed. Text length: ${result?.length ?: 0} characters")
        KSLog.debug("OCR result: $result")

        result.trim()
    }
    
    /**
     * Распознает название торговой точки (merchant name)
     */
    suspend fun recognizeMerchantName(photoBytes: ByteArray, debugMode: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = ByteArrayInputStream(photoBytes)
            val originalImage: BufferedImage = ImageIO.read(inputStream) ?: return@withContext null
            
            val region = ImagePreprocessor.extractAndProcessRegion(
                originalImage,
                ReceiptRegionConfig.MERCHANT,
                invert = true,
                debugMode = debugMode,
                debugName = "merchant"
            ) ?: return@withContext null
            
            val result = merchantTesseract.doOCR(region).trim()
            KSLog.info("Merchant name OCR raw result: '$result'")
            
            // Очищаем результат от артефактов
            val cleaned = result.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length > 3 }
                .firstOrNull()
            
            KSLog.info("Merchant name cleaned: '$cleaned'")
            cleaned
        } catch (e: Exception) {
            KSLog.error("Error recognizing merchant name", e)
            null
        }
    }
    
    /**
     * Распознает сумму транзакции - возвращает сырой текст из региона
     */
    suspend fun recognizeAmount(photoBytes: ByteArray, debugMode: Boolean = false): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val inputStream = ByteArrayInputStream(photoBytes)
            val originalImage: BufferedImage = ImageIO.read(inputStream) ?: return@withContext null
            
            val region = ImagePreprocessor.extractAndProcessRegion(
                originalImage,
                ReceiptRegionConfig.AMOUNT,
                invert = true,
                debugMode = debugMode,
                debugName = "amount"
            ) ?: return@withContext null
            
            val result = amountTesseract.doOCR(region).trim()
            KSLog.info("Amount OCR raw result: '$result'")
            
            // Просто ищем любое число (с минусом или без) и любой символ валюты
            val amountMatch = Regex("""(-?[\d\s]+[,.]?\d*)\s*([^\d\s:]+)""").find(result)
            if (amountMatch != null) {
                val amount = amountMatch.groupValues[1].replace(" ", "").replace(",", ".")
                val currency = amountMatch.groupValues[2].trim()
                KSLog.info("Amount parsed: amount='$amount', currency='$currency'")
                return@withContext Pair(amount, currency)
            }
            
            KSLog.warning("Could not parse amount from: '$result'")
            null
        } catch (e: Exception) {
            KSLog.error("Error recognizing amount", e)
            null
        }
    }
    
    /**
     * Распознает дату и время транзакции
     */
    suspend fun recognizeDateTime(photoBytes: ByteArray, debugMode: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = ByteArrayInputStream(photoBytes)
            val originalImage: BufferedImage = ImageIO.read(inputStream) ?: return@withContext null
            
            val region = ImagePreprocessor.extractAndProcessRegion(
                originalImage,
                ReceiptRegionConfig.DATE_TIME,
                invert = true,
                debugMode = debugMode,
                debugName = "datetime"
            ) ?: return@withContext null
            
            val result = datetimeTesseract.doOCR(region).trim()
            KSLog.info("DateTime OCR raw result: '$result'")
            
            return@withContext result
        } catch (e: Exception) {
            KSLog.error("Error recognizing datetime", e)
            null
        }
    }
    
    /**
     * Распознает номер карты
     */
    suspend fun recognizeCardNumber(photoBytes: ByteArray, debugMode: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = ByteArrayInputStream(photoBytes)
            val originalImage: BufferedImage = ImageIO.read(inputStream) ?: return@withContext null
            
            val region = ImagePreprocessor.extractAndProcessRegion(
                originalImage,
                ReceiptRegionConfig.CARD,
                invert = true,
                debugMode = debugMode,
                debugName = "card"
            ) ?: return@withContext null
            
            val result = cardTesseract.doOCR(region).trim()
            KSLog.info("Card OCR raw result: '$result'")
            
            return@withContext result
        } catch (e: Exception) {
            KSLog.error("Error recognizing card", e)
            null
        }
    }
    
    /**
     * Распознает номер транзакции - возвращает сырой распознанный текст
     */
    suspend fun recognizeTransactionNumber(photoBytes: ByteArray, debugMode: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = ByteArrayInputStream(photoBytes)
            val originalImage: BufferedImage = ImageIO.read(inputStream) ?: return@withContext null
            
            val region = ImagePreprocessor.extractAndProcessRegion(
                originalImage,
                ReceiptRegionConfig.TRANSACTION_NUMBER,
                invert = true,
                debugMode = debugMode,
                debugName = "transaction_number"
            ) ?: return@withContext null
            
            // Применяем бинаризацию для улучшения распознавания цифр
            val binarized = ImagePreprocessor.binarizeImage(region, threshold = 128)
            
            if (debugMode) {
                val debugDir = File("debug_ocr")
                ImageIO.write(binarized, "png", File(debugDir, "transaction_number_binarized.png"))
            }
            
            val result = transactionNumberTesseract.doOCR(binarized).trim()
            KSLog.info("Transaction number OCR raw result: '$result'")
            
            // Возвращаем сырой результат, очищенный от пробелов
            val cleaned = result.replace(Regex("\\s+"), "")
            if (cleaned.isNotEmpty()) {
                KSLog.info("Transaction number cleaned: '$cleaned'")
                return@withContext cleaned
            }
            
            null
        } catch (e: Exception) {
            KSLog.error("Error recognizing transaction number", e)
            null
        }
    }
    
    /**
     * Распознает сумму в другой валюте (Transaction amount) или MCC код
     * В некоторых случаях на месте foreign amount находится MCC код
     */
    suspend fun recognizeForeignAmount(photoBytes: ByteArray, debugMode: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = ByteArrayInputStream(photoBytes)
            val originalImage: BufferedImage = ImageIO.read(inputStream) ?: return@withContext null
            
            val region = ImagePreprocessor.extractAndProcessRegion(
                originalImage,
                ReceiptRegionConfig.FOREIGN_AMOUNT,
                invert = false,  // НЕ инвертируем цвета - текст на фото черный
                debugMode = debugMode,
                debugName = "foreign_amount"
            ) ?: return@withContext null

            val binarized = ImagePreprocessor.binarizeImage(region, threshold = 150)

            if (debugMode) {
                val debugDir = File("debug_ocr")
                debugDir.mkdirs()
                ImageIO.write(binarized, "png", File(debugDir, "foreign_amount_binarized.png"))
                KSLog.info("Saved binarized foreign amount to debug_ocr/foreign_amount_binarized.png")
            }

            // Используем mccTesseract для распознавания цифр
            val result = mccTesseract.doOCR(binarized).trim()
            KSLog.info("Foreign amount OCR raw result: '$result'")
            
            // Очищаем от пробелов и заменяем запятую на точку
            val cleaned = result.replace(Regex("\\s+"), "").replace(",", ".")
            if (cleaned.isNotEmpty()) {
                KSLog.info("Foreign amount cleaned: '$cleaned'")
                return@withContext cleaned
            }
            
            null
        } catch (e: Exception) {
            KSLog.error("Error recognizing foreign amount", e)
            null
        }
    }

    /**
     * Распознает MCC код - возвращает сырой распознанный текст (только цифры)
     * Пробует два региона: после transaction number (новый формат) и в старом месте
     */
    suspend fun recognizeMccCode(photoBytes: ByteArray, hasForeignAmount: Boolean, debugMode: Boolean = false): String? = withContext(Dispatchers.IO) {
        KSLog.info("Starting MCC code recognition (hasForeignAmount=$hasForeignAmount, debug=$debugMode)")
        
        try {
            val inputStream = ByteArrayInputStream(photoBytes)
            val originalImage: BufferedImage = ImageIO.read(inputStream)
                ?: throw IllegalArgumentException("Could not read image from bytes")
            
            // Сохраняем оригинальное изображение для отладки
            if (debugMode) {
                val debugDir = File("debug_ocr")
                debugDir.mkdirs()
                ImageIO.write(originalImage, "png", File(debugDir, "01_original.png"))
                KSLog.info("Saved original image to debug_ocr/01_original.png")
            }
            
            // Сначала пробуем новый формат: MCC код идет сразу после transaction number
            val newFormatRegion = ImagePreprocessor.extractAndProcessRegion(
                originalImage,
                ReceiptRegionConfig.MCC_NEW_FORMAT,
                invert = true,  // текст светлый на темном фоне
                debugMode = debugMode,
                debugName = "mcc_new_format"
            )
            
            if (newFormatRegion != null) {
                // Используем общий tesseract для распознавания текста с цифрами
                val result = tesseract.doOCR(newFormatRegion).trim()
                KSLog.info("MCC OCR (new format) raw result: '$result'")
                
                // Ищем 4-значный код в распознанном тексте
                val mccMatch = Regex("""(\d{4})""").find(result)
                if (mccMatch != null) {
                    val mccCode = mccMatch.groupValues[1]
                    KSLog.info("MCC code found in new format: '$mccCode'")
                    return@withContext mccCode
                }
            }
            
            // Если не нашли в новом формате, пробуем старый регион
            val mccRegion = ImagePreprocessor.extractAndProcessRegion(
                originalImage,
                ReceiptRegionConfig.getMccOldFormatRegion(hasForeignAmount),
                invert = true,
                debugMode = debugMode,
                debugName = "mcc"
            ) ?: return@withContext null
            
            // Сохраняем извлеченный регион для отладки
            if (debugMode) {
                val debugDir = File("debug_ocr")
                ImageIO.write(mccRegion, "png", File(debugDir, "02_mcc_region.png"))
                KSLog.info("Saved MCC region to debug_ocr/02_mcc_region.png")
            }
            
            // Применяем бинаризацию с оптимальным threshold для улучшения распознавания
            val binarized = ImagePreprocessor.binarizeImage(mccRegion, threshold = 150)
            
            if (debugMode) {
                val debugDir = File("debug_ocr")
                ImageIO.write(binarized, "png", File(debugDir, "03_mcc_binarized.png"))
                KSLog.info("Saved binarized MCC region to debug_ocr/03_mcc_binarized.png")
            }
            
            val result = mccTesseract.doOCR(binarized).trim()
            KSLog.info("MCC OCR raw result: '$result'")
            
            // Возвращаем сырой результат, очищенный от пробелов
            val cleaned = result.replace(Regex("\\s+"), "")
            if (cleaned.isNotEmpty()) {
                KSLog.info("MCC code cleaned: '$cleaned'")
                return@withContext cleaned
            }
            
            null
        } catch (e: Exception) {
            KSLog.error("Error during MCC recognition", e)
            return@withContext null
        }
    }

}
