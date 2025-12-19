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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min


object OCRService {

    private val tesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "General",
            pageSegMode = 11 // PSM 11 = Sparse text
        )
    }

    private val merchantTesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "Merchant",
            pageSegMode = 11 // PSM 11 = Sparse text (find as much text as possible)
        )
    }

    private val amountTesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "Amount",
            pageSegMode = 7 // PSM 7 = Single text line
        )
    }

    private val datetimeTesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "DateTime",
            pageSegMode = 7 // PSM 7 = Single text line
        )
    }

    private val cardTesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "Card",
            pageSegMode = 7 // PSM 7 = Single text line
        )
    }

    private val transactionNumberTesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "Transaction Number",
            pageSegMode = 7, // PSM 7 = Single text line
            whitelist = "0123456789",
            additionalVariables = mapOf("classify_bln_numeric_mode" to "1")
        )
    }

    private val mccTesseract: Tesseract by lazy {
        TesseractFactory.create(
            name = "MCC Code",
            pageSegMode = 10, // PSM 10 = Single character
            language = "", // No language for numeric-only
            whitelist = ",.0123456789",
            additionalVariables = mapOf("classify_bln_numeric_mode" to "1")
        )
    }

    suspend fun extractAllFields(photoBytes: ByteArray, debugMode: Boolean = false): ForteTransaction? = withContext(Dispatchers.IO) {
        KSLog.info("Starting comprehensive region-based OCR extraction")
        
        try {
            val merchantName = recognizeMerchantName(photoBytes, debugMode)
            val amount = recognizeAmount(photoBytes, debugMode)
            val dateTime = recognizeDateTime(photoBytes, debugMode)
            val cardNumber = recognizeCardNumber(photoBytes, debugMode)
            val transactionNumber = recognizeTransactionNumber(photoBytes, debugMode)
            val foreignAmount = recognizeForeignAmount(photoBytes, debugMode)
            
            val mccFromRegion = recognizeMccCode(photoBytes, foreignAmount != null && foreignAmount.isNotEmpty(), debugMode)
            
            val mccCode = if (mccFromRegion != null && mccFromRegion.matches(Regex("^\\d{4}$"))) {
                KSLog.info("MCC code found in dedicated region: '$mccFromRegion'")
                mccFromRegion
            } else if (foreignAmount != null && foreignAmount.matches(Regex("^\\d{4}$"))) {
                KSLog.info("Foreign amount '$foreignAmount' is 4-digit code, using as MCC fallback")
                foreignAmount
            } else if (foreignAmount != null && foreignAmount.matches(Regex("^\\d{5,}$"))) {
                val firstFour = foreignAmount.take(4)
                KSLog.info("Foreign amount '$foreignAmount' has 5+ digits, taking first 4 as MCC: '$firstFour'")
                firstFour
            } else {
                null
            }
            
            val actualForeignAmount = if (foreignAmount != null && foreignAmount.matches(Regex("^\\d{4,}$"))) {
                null
            } else {
                foreignAmount
            }
            
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

    private fun parseForteDateTime(forteDateTime: String): ZonedDateTime? {
        return try {
            val cleanedDate = forteDateTime
                .replace("'s", "")
                .replace(Regex("^O"), "0")
                .replace(Regex("^0+(\\d{2})"), "$1")
                .trim()

            val inputFormatter = java.time.format.DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("dd MMMM yyyy HH:mm:ss")
                .toFormatter(Locale.ENGLISH)

            val localDateTime = LocalDateTime.parse(cleanedDate, inputFormatter)
            val almatyZone = ZoneId.of("Asia/Almaty")
            ZonedDateTime.of(localDateTime, almatyZone)
        } catch (e: Exception) {
            KSLog.error("Error parsing date: '$forteDateTime'", e)
            null
        }
    }

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
            
            val binarized = ImagePreprocessor.binarizeImage(region, threshold = 128)
            
            if (debugMode) {
                val debugDir = File("debug_ocr")
                ImageIO.write(binarized, "png", File(debugDir, "transaction_number_binarized.png"))
            }
            
            val result = transactionNumberTesseract.doOCR(binarized).trim()
            KSLog.info("Transaction number OCR raw result: '$result'")
            
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

    suspend fun recognizeForeignAmount(photoBytes: ByteArray, debugMode: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            val inputStream = ByteArrayInputStream(photoBytes)
            val originalImage: BufferedImage = ImageIO.read(inputStream) ?: return@withContext null
            
            val region = ImagePreprocessor.extractAndProcessRegion(
                originalImage,
                ReceiptRegionConfig.FOREIGN_AMOUNT,
                invert = false,
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

            val result = mccTesseract.doOCR(binarized).trim()
            KSLog.info("Foreign amount OCR raw result: '$result'")
            
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

    suspend fun recognizeMccCode(photoBytes: ByteArray, hasForeignAmount: Boolean, debugMode: Boolean = false): String? = withContext(Dispatchers.IO) {
        KSLog.info("Starting MCC code recognition (hasForeignAmount=$hasForeignAmount, debug=$debugMode)")
        
        try {
            val inputStream = ByteArrayInputStream(photoBytes)
            val originalImage: BufferedImage = ImageIO.read(inputStream)
                ?: throw IllegalArgumentException("Could not read image from bytes")
            
            if (debugMode) {
                val debugDir = File("debug_ocr")
                debugDir.mkdirs()
                ImageIO.write(originalImage, "png", File(debugDir, "01_original.png"))
                KSLog.info("Saved original image to debug_ocr/01_original.png")
            }
            
            val newFormatRegion = ImagePreprocessor.extractAndProcessRegion(
                originalImage,
                ReceiptRegionConfig.MCC_NEW_FORMAT,
                invert = true,
                debugMode = debugMode,
                debugName = "mcc_new_format"
            )
            
            if (newFormatRegion != null) {
                val result = tesseract.doOCR(newFormatRegion).trim()
                KSLog.info("MCC OCR (new format) raw result: '$result'")
                
                val mccMatch = Regex("""(\d{4})""").find(result)
                if (mccMatch != null) {
                    val mccCode = mccMatch.groupValues[1]
                    KSLog.info("MCC code found in new format: '$mccCode'")
                    return@withContext mccCode
                }
            }

            val mccRegion = ImagePreprocessor.extractAndProcessRegion(
                originalImage,
                ReceiptRegionConfig.getMccOldFormatRegion(hasForeignAmount),
                invert = true,
                debugMode = debugMode,
                debugName = "mcc"
            ) ?: return@withContext null
            
            if (debugMode) {
                val debugDir = File("debug_ocr")
                ImageIO.write(mccRegion, "png", File(debugDir, "02_mcc_region.png"))
                KSLog.info("Saved MCC region to debug_ocr/02_mcc_region.png")
            }
            
            val binarized = ImagePreprocessor.binarizeImage(mccRegion, threshold = 150)
            
            if (debugMode) {
                val debugDir = File("debug_ocr")
                ImageIO.write(binarized, "png", File(debugDir, "03_mcc_binarized.png"))
                KSLog.info("Saved binarized MCC region to debug_ocr/03_mcc_binarized.png")
            }
            
            val result = mccTesseract.doOCR(binarized).trim()
            KSLog.info("MCC OCR raw result: '$result'")
            
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
