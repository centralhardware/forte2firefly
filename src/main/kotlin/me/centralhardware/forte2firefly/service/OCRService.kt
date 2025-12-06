package me.centralhardware.forte2firefly.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sourceforge.tess4j.Tesseract
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.debug
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO


class OCRService(
    tessdataPath: String? = null,
    language: String = "eng"
) {
    private val tesseract: Tesseract = Tesseract()

    init {
        try {
            tessdataPath?.let { tesseract.setDatapath(it) }
            tesseract.setLanguage(language)

            tesseract.setPageSegMode(6)
            tesseract.setOcrEngineMode(1)

            KSLog.info("Tesseract OCR initialized successfully with language: $language")
            if (tessdataPath != null) {
                KSLog.info("Using tessdata path: $tessdataPath")
            }
        } catch (e: Exception) {
            KSLog.error("Error initializing Tesseract", e)
            throw IllegalStateException("Failed to initialize Tesseract OCR. Make sure Tesseract is installed.", e)
        }
    }



    suspend fun recognizeText(photoBytes: ByteArray): String = withContext(Dispatchers.IO) {
        KSLog.info("Starting OCR with preprocessing for image (${photoBytes.size} bytes)")

        val inputStream = ByteArrayInputStream(photoBytes)
        val originalImage: BufferedImage = ImageIO.read(inputStream)
            ?: throw IllegalArgumentException("Could not read image from bytes")

        val result = tesseract.doOCR(originalImage)

        KSLog.info("OCR with preprocessing completed. Text length: ${result.length} characters")
        KSLog.debug("OCR result: $result")

        result.trim()
    }

}
