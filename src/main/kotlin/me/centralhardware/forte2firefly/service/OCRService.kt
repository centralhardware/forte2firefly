package me.centralhardware.forte2firefly.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.centralhardware.forte2firefly.Config
import net.sourceforge.tess4j.Tesseract
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.debug
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min


object OCRService {
    private val tesseract: Tesseract by lazy {
        Tesseract().apply {
            try {
                // Try to get tessdata path from Config, fallback to env variable or default
                val tessdataPath = runCatching { Config.tessdataPrefix }.getOrNull()
                    ?: System.getenv("TESSDATA_PREFIX")
                    ?: "/usr/share/tesseract-ocr/5/tessdata/"

                setDatapath(tessdataPath)
                setLanguage("eng")

                // PSM 3 = Fully automatic page segmentation, but no OSD
                setPageSegMode(11)
                // OEM 1 = Neural nets LSTM engine only (best accuracy)
                setOcrEngineMode(1)

                KSLog.info("Tesseract OCR initialized successfully with language: eng")
                KSLog.info("Using tessdata path: $tessdataPath")
            } catch (e: Exception) {
                KSLog.error("Error initializing Tesseract", e)
                throw IllegalStateException("Failed to initialize Tesseract OCR. Make sure Tesseract is installed.", e)
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

}
