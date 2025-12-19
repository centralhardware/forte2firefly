package me.centralhardware.forte2firefly.service

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info
import dev.inmo.kslog.common.warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.centralhardware.forte2firefly.model.ForteTransaction
import net.sourceforge.tess4j.Tesseract
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Конфигурация для OCR поля: регион изображения, тип Tesseract, пост-обработка
 */
sealed class OcrFieldConfig(
    val name: String,
    val region: ReceiptRegionConfig.Region,
    val tesseractType: TesseractType,
    val invert: Boolean = true,
    val binarize: Boolean = false,
    val binarizeThreshold: Int = 128
) {
    data object Merchant : OcrFieldConfig(
        name = "merchant",
        region = ReceiptRegionConfig.MERCHANT,
        tesseractType = TesseractType.SPARSE
    )

    data object Amount : OcrFieldConfig(
        name = "amount",
        region = ReceiptRegionConfig.AMOUNT,
        tesseractType = TesseractType.SINGLE_LINE
    )

    data object DateTime : OcrFieldConfig(
        name = "datetime",
        region = ReceiptRegionConfig.DATE_TIME,
        tesseractType = TesseractType.SINGLE_LINE
    )

    data object Card : OcrFieldConfig(
        name = "card",
        region = ReceiptRegionConfig.CARD,
        tesseractType = TesseractType.SINGLE_LINE
    )

    data object TransactionNumber : OcrFieldConfig(
        name = "transaction_number",
        region = ReceiptRegionConfig.TRANSACTION_NUMBER,
        tesseractType = TesseractType.NUMERIC,
        binarize = true,
        binarizeThreshold = 128
    )

    data object ForeignAmount : OcrFieldConfig(
        name = "foreign_amount",
        region = ReceiptRegionConfig.FOREIGN_AMOUNT,
        tesseractType = TesseractType.NUMERIC,
        invert = false,
        binarize = true,
        binarizeThreshold = 150
    )

    data object MccNewFormat : OcrFieldConfig(
        name = "mcc_new_format",
        region = ReceiptRegionConfig.MCC_NEW_FORMAT,
        tesseractType = TesseractType.SPARSE
    )

    class MccOldFormat(hasForeignAmount: Boolean) : OcrFieldConfig(
        name = "mcc",
        region = ReceiptRegionConfig.getMccOldFormatRegion(hasForeignAmount),
        tesseractType = TesseractType.NUMERIC,
        binarize = true,
        binarizeThreshold = 150
    )
}

/**
 * Типы инстансов Tesseract с разными настройками
 */
enum class TesseractType {
    SPARSE,      // PSM 11 - для разреженного текста (merchant, общий)
    SINGLE_LINE, // PSM 7 - для одной строки (amount, datetime, card)
    NUMERIC      // PSM 10 - для цифр (transaction number, MCC, foreign amount)
}

object OCRService {

    private val tesseractInstances: Map<TesseractType, Tesseract> by lazy {
        mapOf(
            TesseractType.SPARSE to TesseractFactory.create(
                name = "Sparse",
                pageSegMode = 11
            ),
            TesseractType.SINGLE_LINE to TesseractFactory.create(
                name = "SingleLine",
                pageSegMode = 7
            ),
            TesseractType.NUMERIC to TesseractFactory.create(
                name = "Numeric",
                pageSegMode = 10,
                language = "",
                whitelist = ",.0123456789",
                additionalVariables = mapOf("classify_bln_numeric_mode" to "1")
            )
        )
    }

    private fun getTesseract(type: TesseractType): Tesseract =
        tesseractInstances[type] ?: error("Tesseract instance not found for $type")

    /**
     * Распознает поле изображения по конфигурации
     */
    private fun recognizeField(
        image: BufferedImage,
        config: OcrFieldConfig,
        debugMode: Boolean
    ): String? {
        return try {
            var region = ImagePreprocessor.extractAndProcessRegion(
                image,
                config.region,
                invert = config.invert,
                debugMode = debugMode,
                debugName = config.name
            ) ?: return null

            if (config.binarize) {
                region = ImagePreprocessor.binarizeImage(region, config.binarizeThreshold)
                if (debugMode) {
                    saveDebugImage(region, "${config.name}_binarized")
                }
            }

            val result = getTesseract(config.tesseractType).doOCR(region).trim()
            KSLog.info("${config.name} OCR raw result: '$result'")
            result.ifEmpty { null }
        } catch (e: Exception) {
            KSLog.error("Error recognizing ${config.name}", e)
            null
        }
    }

    private fun saveDebugImage(image: BufferedImage, name: String) {
        val debugDir = File("debug_ocr").apply { mkdirs() }
        ImageIO.write(image, "png", File(debugDir, "$name.png"))
        KSLog.info("Saved $name to debug_ocr/$name.png")
    }

    /**
     * Извлекает все поля транзакции из изображения чека
     */
    suspend fun extractAllFields(photoBytes: ByteArray, debugMode: Boolean = false): ForteTransaction? =
        withContext(Dispatchers.IO) {
            KSLog.info("Starting comprehensive region-based OCR extraction")

            try {
                val image = readImage(photoBytes) ?: return@withContext null

                if (debugMode) {
                    saveDebugImage(image, "01_original")
                }

                val extractedFields = extractFields(image, debugMode)
                    ?: return@withContext null

                buildTransaction(extractedFields)
            } catch (e: Exception) {
                KSLog.error("Error during comprehensive OCR extraction", e)
                null
            }
        }

    private fun readImage(photoBytes: ByteArray): BufferedImage? {
        return ByteArrayInputStream(photoBytes).use { stream ->
            ImageIO.read(stream)
        }
    }

    /**
     * Данные, извлеченные из чека
     */
    private data class ExtractedFields(
        val merchantName: String,
        val amount: Pair<String, String>,
        val dateTime: String,
        val cardNumber: String,
        val transactionNumber: String,
        val foreignAmount: String?,
        val mccCode: String?
    )

    private fun extractFields(image: BufferedImage, debugMode: Boolean): ExtractedFields? {
        val merchantName = extractMerchantName(image, debugMode)
        if (merchantName == null) {
            KSLog.warning("Merchant name not found")
            return null
        }

        val amount = extractAmount(image, debugMode)
        if (amount == null) {
            KSLog.warning("Amount not found")
            return null
        }

        val dateTime = recognizeField(image, OcrFieldConfig.DateTime, debugMode)
        if (dateTime == null) {
            KSLog.warning("DateTime not found")
            return null
        }

        val cardNumber = recognizeField(image, OcrFieldConfig.Card, debugMode)
        if (cardNumber == null) {
            KSLog.warning("Card number not found")
            return null
        }

        val transactionNumber = extractTransactionNumber(image, debugMode)
        if (transactionNumber == null) {
            KSLog.warning("Transaction number not found")
            return null
        }

        val foreignAmount = extractForeignAmount(image, debugMode)
        val mccCode = extractMccCode(image, foreignAmount, debugMode)

        return ExtractedFields(
            merchantName = merchantName,
            amount = amount,
            dateTime = dateTime,
            cardNumber = cardNumber,
            transactionNumber = transactionNumber,
            foreignAmount = filterMccFromForeignAmount(foreignAmount),
            mccCode = mccCode
        )
    }

    private fun extractMerchantName(image: BufferedImage, debugMode: Boolean): String? {
        val result = recognizeField(image, OcrFieldConfig.Merchant, debugMode)
            ?: return null

        return result.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length > 3 }
            .firstOrNull()
            .also { KSLog.info("Merchant name cleaned: '$it'") }
    }

    private fun extractAmount(image: BufferedImage, debugMode: Boolean): Pair<String, String>? {
        val result = recognizeField(image, OcrFieldConfig.Amount, debugMode)
            ?: return null

        val amountMatch = Regex("""(-?[\d\s]+[,.]?\d*)\s*([^\d\s:]+)""").find(result)
        if (amountMatch != null) {
            val amountValue = amountMatch.groupValues[1].replace(" ", "").replace(",", ".")
            val currency = amountMatch.groupValues[2].trim()
            KSLog.info("Amount parsed: amount='$amountValue', currency='$currency'")
            return Pair(amountValue, currency)
        }

        KSLog.warning("Could not parse amount from: '$result'")
        return null
    }

    private fun extractTransactionNumber(image: BufferedImage, debugMode: Boolean): String? {
        val result = recognizeField(image, OcrFieldConfig.TransactionNumber, debugMode)
            ?: return null

        val cleaned = result.replace(Regex("\\s+"), "")
        return cleaned.ifEmpty { null }
            .also { KSLog.info("Transaction number cleaned: '$it'") }
    }

    private fun extractForeignAmount(image: BufferedImage, debugMode: Boolean): String? {
        val result = recognizeField(image, OcrFieldConfig.ForeignAmount, debugMode)
            ?: return null

        val cleaned = result.replace(Regex("\\s+"), "").replace(",", ".")
        return cleaned.ifEmpty { null }
            .also { KSLog.info("Foreign amount cleaned: '$it'") }
    }

    private fun extractMccCode(image: BufferedImage, foreignAmount: String?, debugMode: Boolean): String? {
        // Сначала пробуем новый формат
        val newFormatResult = recognizeField(image, OcrFieldConfig.MccNewFormat, debugMode)
        if (newFormatResult != null) {
            val mccMatch = Regex("""(\d{4})""").find(newFormatResult)
            if (mccMatch != null) {
                val mccCode = mccMatch.groupValues[1]
                KSLog.info("MCC code found in new format: '$mccCode'")
                return mccCode
            }
        }

        // Пробуем старый формат
        val hasForeignAmount = foreignAmount != null && foreignAmount.isNotEmpty()
        val oldFormatResult = recognizeField(image, OcrFieldConfig.MccOldFormat(hasForeignAmount), debugMode)
        if (oldFormatResult != null) {
            val cleaned = oldFormatResult.replace(Regex("\\s+"), "")
            if (cleaned.isNotEmpty()) {
                KSLog.info("MCC code from old format: '$cleaned'")
                return cleaned
            }
        }

        // Fallback: используем foreignAmount если это 4+ цифр
        return when {
            foreignAmount?.matches(Regex("^\\d{4}$")) == true -> {
                KSLog.info("Foreign amount '$foreignAmount' is 4-digit code, using as MCC fallback")
                foreignAmount
            }
            foreignAmount?.matches(Regex("^\\d{5,}$")) == true -> {
                val firstFour = foreignAmount.take(4)
                KSLog.info("Foreign amount '$foreignAmount' has 5+ digits, taking first 4 as MCC: '$firstFour'")
                firstFour
            }
            else -> null
        }
    }

    private fun filterMccFromForeignAmount(foreignAmount: String?): String? {
        return if (foreignAmount?.matches(Regex("^\\d{4,}$")) == true) null else foreignAmount
    }

    private fun buildTransaction(fields: ExtractedFields): ForteTransaction? {
        val parsedDateTime = parseForteDateTime(fields.dateTime)
        if (parsedDateTime == null) {
            KSLog.warning("Could not parse datetime: ${fields.dateTime}")
            return null
        }

        val (amountValue, currencySymbol) = fields.amount

        return ForteTransaction(
            description = fields.merchantName,
            amount = amountValue.removePrefix("-"),
            currencySymbol = currencySymbol,
            dateTime = parsedDateTime,
            from = fields.cardNumber,
            transactionNumber = fields.transactionNumber,
            transactionAmount = fields.foreignAmount,
            mccCode = fields.mccCode
        ).also {
            KSLog.info("Successfully extracted transaction: $it")
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

}
