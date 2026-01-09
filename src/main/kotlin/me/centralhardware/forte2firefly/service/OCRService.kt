package me.centralhardware.forte2firefly.service

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info
import dev.inmo.kslog.common.warning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.centralhardware.forte2firefly.model.ForteTransaction
import net.sourceforge.tess4j.Tesseract
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import javax.imageio.ImageIO

object OCRService {

    private val tesseractInstances: Map<TesseractType, Tesseract> by lazy {
        mapOf(
            TesseractType.SPARSE to TesseractFactory.create(
                name = "Sparse",
                pageSegMode = TesseractType.SPARSE.pageSegMode
            ),
            TesseractType.SINGLE_LINE to TesseractFactory.create(
                name = "SingleLine",
                pageSegMode = TesseractType.SINGLE_LINE.pageSegMode
            ),
            TesseractType.NUMERIC to TesseractFactory.create(
                name = "Numeric",
                pageSegMode = TesseractType.NUMERIC.pageSegMode,
                language = "",
                whitelist = ",.0123456789",
                additionalVariables = mapOf("classify_bln_numeric_mode" to "1")
            ),
            TesseractType.NUMERIC_LINE to TesseractFactory.create(
                name = "NumericLine",
                pageSegMode = TesseractType.NUMERIC_LINE.pageSegMode,
                language = "",
                whitelist = ",.0123456789"
            )
        )
    }

    private fun getTesseract(type: TesseractType): Tesseract =
        tesseractInstances[type] ?: error("Tesseract instance not found for $type")

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

            if (config.scale > 1) {
                val scaledWidth = region.width * config.scale
                val scaledHeight = region.height * config.scale
                val scaled = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB)
                val g2d = scaled.createGraphics()
                try {
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2d.drawImage(region, 0, 0, scaledWidth, scaledHeight, null)
                } finally {
                    g2d.dispose()
                }
                region = scaled
            }

            if (config.binarize) {
                region = ImagePreprocessor.binarizeImage(region, config.binarizeThreshold)
                if (debugMode) {
                    saveDebugImage(region, "${config.name}_binarized")
                }
            }

            val tesseract = getTesseract(config.tesseractType)
            if (config.whitelist != null) {
                tesseract.setVariable("tessedit_char_whitelist", config.whitelist)
            }
            val result = tesseract.doOCR(region).trim()
            if (config.whitelist != null) {
                tesseract.setVariable("tessedit_char_whitelist", "")
            }
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
            foreignAmount = filterMccFromForeignAmount(foreignAmount, mccCode),
            mccCode = mccCode
        )
    }

    private fun extractMerchantName(image: BufferedImage, debugMode: Boolean): String? {
        val result = recognizeField(image, OcrFieldConfig.Merchant, debugMode)
            ?: return null

        return result.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it.length > 3 }
            ?.let { cleanMerchantName(it) }
            .also { KSLog.info("Merchant name cleaned: '$it'") }
    }

    private fun cleanMerchantName(name: String): String {
        return name
            .replace("!|", "I")
            .replace("!l", "I")
            .replace("l|", "I")
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

        val numericMatch = Regex("""(\d+(?:[.,]\d+)?)""").find(result)
        if (numericMatch != null) {
            val cleaned = numericMatch.groupValues[1].replace(",", ".")
            return cleaned.also { KSLog.info("Foreign amount cleaned: '$it'") }
        }

        val cleanedResult = result.replace(Regex("\\s+"), "")
            .replace(",", ".")
            .replace(Regex("[.,]+$"), "")

        return cleanedResult.ifEmpty { null }
            .also { KSLog.info("Foreign amount cleaned: '$it'") }
    }

    private fun extractMccCode(image: BufferedImage, foreignAmount: String?, debugMode: Boolean): String? {
        val newFormatResult = recognizeField(image, OcrFieldConfig.MccNewFormat, debugMode)
        if (newFormatResult != null) {
            val mccMatch = Regex("""(\d{4})""").find(newFormatResult)
            if (mccMatch != null) {
                val mccCode = mccMatch.groupValues[1]
                KSLog.info("MCC code found in new format: '$mccCode'")
                return mccCode
            }
        }

        val hasForeignAmount = !foreignAmount.isNullOrEmpty()
        val oldFormatResult = recognizeField(image, OcrFieldConfig.MccOldFormat(hasForeignAmount), debugMode)
        if (oldFormatResult != null) {
            val cleaned = oldFormatResult.replace(Regex("\\s+"), "")
            if (cleaned.isNotEmpty()) {
                KSLog.info("MCC code from old format: '$cleaned'")
                return cleaned
            }
        }

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

    private fun filterMccFromForeignAmount(foreignAmount: String?, mccCode: String?): String? {
        if (mccCode != null && mccCode != foreignAmount) {
            return foreignAmount
        }
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
                .replace(Regex("^Q"), "0")
                .replace(Regex("^0+(\\d{2})"), "$1")
                .replace(Regex("(january|february|march|april|may|june|july|august|september|october|november|december)s\\b", RegexOption.IGNORE_CASE), "$1")
                .trim()

            val inputFormatter = DateTimeFormatterBuilder()
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
