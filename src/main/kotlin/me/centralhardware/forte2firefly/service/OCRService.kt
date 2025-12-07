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
    
    private fun getTessdataPath(): String {
        return runCatching { Config.tessdataPrefix }.getOrNull()
            ?: System.getenv("TESSDATA_PREFIX")
            ?: "/usr/share/tesseract-ocr/5/tessdata/"
    }
    
    // Общий Tesseract для fallback
    private val tesseract: Tesseract by lazy {
        Tesseract().apply {
            try {
                setDatapath(getTessdataPath())
                setLanguage("eng")
                setPageSegMode(11) // PSM 11 = Sparse text
                setOcrEngineMode(1) // OEM 1 = LSTM only
                setVariable("load_system_dawg", "false")
                setVariable("load_freq_dawg", "false")
                KSLog.info("General Tesseract OCR initialized")
            } catch (e: Exception) {
                KSLog.error("Error initializing Tesseract", e)
                throw IllegalStateException("Failed to initialize Tesseract OCR. Make sure Tesseract is installed.", e)
            }
        }
    }
    
    // Tesseract для merchant name (текст с заглавными буквами)
    private val merchantTesseract: Tesseract by lazy {
        Tesseract().apply {
            try {
                setDatapath(getTessdataPath())
                setLanguage("eng")
                setPageSegMode(11) // PSM 11 = Sparse text (find as much text as possible)
                setOcrEngineMode(1)
                setVariable("load_system_dawg", "false")
                setVariable("load_freq_dawg", "false")
                KSLog.info("Merchant Tesseract OCR initialized (PSM 11)")
            } catch (e: Exception) {
                KSLog.error("Error initializing merchant Tesseract", e)
                throw IllegalStateException("Failed to initialize merchant Tesseract OCR.", e)
            }
        }
    }
    
    // Tesseract для amount (цифры + символы валют)
    private val amountTesseract: Tesseract by lazy {
        Tesseract().apply {
            try {
                setDatapath(getTessdataPath())
                setLanguage("eng")
                setPageSegMode(7) // PSM 7 = Single text line
                setOcrEngineMode(1)
                setVariable("load_system_dawg", "false")
                setVariable("load_freq_dawg", "false")
                KSLog.info("Amount Tesseract OCR initialized (PSM 7)")
            } catch (e: Exception) {
                KSLog.error("Error initializing amount Tesseract", e)
                throw IllegalStateException("Failed to initialize amount Tesseract OCR.", e)
            }
        }
    }
    
    // Tesseract для datetime (текст с датой и временем)
    private val datetimeTesseract: Tesseract by lazy {
        Tesseract().apply {
            try {
                setDatapath(getTessdataPath())
                setLanguage("eng")
                setPageSegMode(7) // PSM 7 = Single text line
                setOcrEngineMode(1)
                setVariable("load_system_dawg", "false")
                setVariable("load_freq_dawg", "false")
                KSLog.info("DateTime Tesseract OCR initialized (PSM 7)")
            } catch (e: Exception) {
                KSLog.error("Error initializing datetime Tesseract", e)
                throw IllegalStateException("Failed to initialize datetime Tesseract OCR.", e)
            }
        }
    }
    
    // Tesseract для card number (текст с номером карты)
    private val cardTesseract: Tesseract by lazy {
        Tesseract().apply {
            try {
                setDatapath(getTessdataPath())
                setLanguage("eng")
                setPageSegMode(7) // PSM 7 = Single text line
                setOcrEngineMode(1)
                setVariable("load_system_dawg", "false")
                setVariable("load_freq_dawg", "false")
                KSLog.info("Card Tesseract OCR initialized (PSM 7)")
            } catch (e: Exception) {
                KSLog.error("Error initializing card Tesseract", e)
                throw IllegalStateException("Failed to initialize card Tesseract OCR.", e)
            }
        }
    }
    
    // Tesseract для transaction number (только цифры)
    private val transactionNumberTesseract: Tesseract by lazy {
        Tesseract().apply {
            try {
                setDatapath(getTessdataPath())
                setLanguage("eng")
                setPageSegMode(7) // PSM 7 = Single text line
                setOcrEngineMode(1)
                setVariable("tessedit_char_whitelist", "0123456789")
                setVariable("load_system_dawg", "false")
                setVariable("load_freq_dawg", "false")
                setVariable("classify_bln_numeric_mode", "1")
                KSLog.info("Transaction Number Tesseract OCR initialized (digits only, PSM 7)")
            } catch (e: Exception) {
                KSLog.error("Error initializing transaction number Tesseract", e)
                throw IllegalStateException("Failed to initialize transaction number Tesseract OCR.", e)
            }
        }
    }

    // Tesseract для MCC code (4 цифры)
    private val mccTesseract: Tesseract by lazy {
        Tesseract().apply {
            try {
                setDatapath(getTessdataPath())
//                setLanguage("eng")
                setPageSegMode(10) // PSM 7 = Single line
                setOcrEngineMode(1) // LSTM only
                setVariable("tessedit_char_whitelist", ",.0123456789")
                setVariable("classify_bln_numeric_mode", "1")
                setVariable("load_system_dawg", "0")
                setVariable("load_freq_dawg", "0")
                KSLog.info("MCC Code Tesseract OCR initialized (PSM 7, digits only)")
            } catch (e: Exception) {
                KSLog.error("Error initializing MCC Tesseract", e)
                throw IllegalStateException("Failed to initialize MCC Tesseract OCR.", e)
            }
        }
    }

    private fun preprocessImage(image: BufferedImage): BufferedImage {
        // Обрезаем верхние 15% где статус бар и UI элементы
        val cropTopPercent = 0.10
        val cropY = (image.height * cropTopPercent).toInt()
        val croppedHeight = image.height - cropY

        val croppedImage = image.getSubimage(0, cropY, image.width, croppedHeight)

        // Увеличиваем размер в 2x для лучшего распознавания
        val scaleFactor = 2.0
        val scaledWidth = (croppedImage.width * scaleFactor).toInt()
        val scaledHeight = (croppedImage.height * scaleFactor).toInt()

        val scaledImage = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB)
        val g2d = scaledImage.createGraphics()

        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        g2d.drawImage(croppedImage, 0, 0, scaledWidth, scaledHeight, null)
        g2d.dispose()

        return scaledImage
    }

    /**
     * Инвертирует цвета изображения (темный фон → светлый, светлый текст → темный)
     * Это необходимо для лучшего распознавания белого текста на темном фоне
     */
    private fun invertColors(image: BufferedImage): BufferedImage {
        val inverted = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val r = 255 - ((rgb shr 16) and 0xFF)
                val g = 255 - ((rgb shr 8) and 0xFF)
                val b = 255 - (rgb and 0xFF)
                val invertedRgb = (r shl 16) or (g shl 8) or b
                inverted.setRGB(x, y, invertedRgb)
            }
        }
        return inverted
    }
    
    /**
     * Применяет бинаризацию к изображению (превращает в чёрно-белое)
     * Это улучшает распознавание текста, особенно для цифр
     */
    private fun binarizeImage(image: BufferedImage, threshold: Int = 128): BufferedImage {
        val binarized = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                val gray = (r + g + b) / 3
                val binary = if (gray > threshold) 255 else 0
                val binaryRgb = (binary shl 16) or (binary shl 8) or binary
                binarized.setRGB(x, y, binaryRgb)
            }
        }
        return binarized
    }
    
    /**
     * Извлекает и обрабатывает регион изображения для OCR (с абсолютными пикселями)
     */
    private fun extractAndProcessRegion(
        image: BufferedImage,
        startYPixels: Int,
        endYPixels: Int,
        startXPixels: Int = 0,
        endXPixels: Int = -1,
        invert: Boolean = true,
        debugMode: Boolean = false,
        debugName: String = "region"
    ): BufferedImage? {
        return try {
            val startY = startYPixels
            val endY = endYPixels
            val startX = startXPixels
            val endX = if (endXPixels == -1) image.width else endXPixels
            
            val regionHeight = endY - startY
            val regionWidth = endX - startX
            
            if (regionHeight <= 0 || regionWidth <= 0) {
                KSLog.info("Invalid region dimensions for $debugName: ${regionWidth}x$regionHeight")
                return null
            }
            
            KSLog.debug("Extracting $debugName region: X=$startX-$endX, Y=$startY-$endY (${regionWidth}x${regionHeight}px)")
            
            val region = image.getSubimage(startX, startY, regionWidth, regionHeight)
            
            // Масштабирование
            val scaledWidth = (region.width * 1)
            val scaledHeight = (region.height * 1)
            
            val scaledRegion = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB)
            val g2d = scaledRegion.createGraphics()
            
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            g2d.drawImage(region, 0, 0, scaledWidth, scaledHeight, null)
            g2d.dispose()
            
            // Инверсия (если нужна)
            val result = if (invert) invertColors(scaledRegion) else scaledRegion
            
            if (debugMode) {
                val debugDir = File("debug_ocr")
                debugDir.mkdirs()
                ImageIO.write(result, "png", File(debugDir, "${debugName}_processed.png"))
                KSLog.info("Saved $debugName region to debug_ocr/${debugName}_processed.png")
            }
            
            result
        } catch (e: Exception) {
            KSLog.error("Error extracting $debugName region", e)
            null
        }
    }

    /**
     * Извлекает регион изображения, где предположительно находится MCC код
     * На чеках Forte MCC код обычно находится в нижней части изображения
     * Позиция зависит от наличия foreign amount
     */
    private fun extractMccRegion(image: BufferedImage, hasForeignAmount: Boolean, debugMode: Boolean = false): BufferedImage? {
        // MCC находится в разных позициях в зависимости от наличия foreign amount
        val (startY, endY) = if (hasForeignAmount) {
            // С foreign amount: MCC ниже
            Pair(2065, 2105)
        } else {
            // Без foreign amount: MCC выше (примерно на месте где был бы foreign amount)
            Pair(1950, 1990)
        }
        
        // X координаты одинаковые: X=25-200px (с запасом чтобы захватить все 4 цифры)
        return extractAndProcessRegion(
            image, 
            startYPixels = startY, 
            endYPixels = endY,
            startXPixels = 25,
            endXPixels = 200,
            invert = true,
            debugMode = debugMode, 
            debugName = "mcc"
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
     * Конвертирует ZonedDateTime в формат для Firefly III
     */
    fun convertToFireflyDate(zonedDateTime: ZonedDateTime): String {
        val utcZone = java.time.ZoneId.of("UTC")
        val utcTime = zonedDateTime.withZoneSameInstant(utcZone)
        val adjustedTime = utcTime.plusHours(1)
        val result = adjustedTime.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        KSLog.debug("Converted date: ${zonedDateTime.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)} (${zonedDateTime.zone}) -> ${utcTime.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)} (UTC) -> $result (UTC+1h for Firefly)")
        return result
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
        val preprocessedImage = preprocessImage(originalImage)

        val result = tesseract.doOCR(preprocessedImage)

        KSLog.info("OCR with preprocessing completed. Text length: ${result.length} characters")
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
            
            // Merchant: 586-742px (from Label Studio markup)
            val region = extractAndProcessRegion(
                originalImage, 586, 742, invert = true, debugMode = debugMode, debugName = "merchant"
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
            
            // Amount: 714-846px (from Label Studio markup)
            val region = extractAndProcessRegion(
                originalImage, 714, 846, invert = true, debugMode = debugMode, debugName = "amount"
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
            
            // DateTime: 1284-1396px (from Label Studio markup)
            val region = extractAndProcessRegion(
                originalImage, 1284, 1396, invert = true, debugMode = debugMode, debugName = "datetime"
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
            
            // Card: 1485-1581px (from Label Studio markup)
            val region = extractAndProcessRegion(
                originalImage, 1485, 1581, invert = true, debugMode = debugMode, debugName = "card"
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
            
            // Transaction Number: 1661-1769px (from Label Studio markup)
            val region = extractAndProcessRegion(
                originalImage, 1661, 1769,
                 invert = true, debugMode = debugMode, debugName = "transaction_number"
            ) ?: return@withContext null
            
            // Применяем бинаризацию для улучшения распознавания цифр
            val binarized = binarizeImage(region, threshold = 128)
            
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
            
            // Foreign Amount: 1850-1954px 
            val region = extractAndProcessRegion(
                originalImage, 
                startYPixels = 1850, 
                endYPixels = 1954,
                startXPixels = 25,
                endXPixels = 200,
                invert = false,  // НЕ инвертируем цвета - текст на фото черный
                debugMode = debugMode, 
                debugName = "foreign_amount"
            ) ?: return@withContext null

            val binarized = binarizeImage(region, threshold = 150)

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
            // Регион: Y=960-1010px (MCC код "5734", с учетом crop 10% = 157px)
            val newFormatRegion = extractAndProcessRegion(
                originalImage,
                startYPixels = 960,
                endYPixels = 1010,
                startXPixels = 25,
                endXPixels = 200,
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
            val mccRegion = extractMccRegion(originalImage, hasForeignAmount, debugMode)
            if (mccRegion == null) {
                KSLog.info("Could not extract MCC region")
                return@withContext null
            }
            
            // Сохраняем извлеченный регион для отладки
            if (debugMode) {
                val debugDir = File("debug_ocr")
                ImageIO.write(mccRegion, "png", File(debugDir, "02_mcc_region.png"))
                KSLog.info("Saved MCC region to debug_ocr/02_mcc_region.png")
            }
            
            // Применяем бинаризацию с оптимальным threshold для улучшения распознавания
            val binarized = binarizeImage(mccRegion, threshold = 150)
            
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
