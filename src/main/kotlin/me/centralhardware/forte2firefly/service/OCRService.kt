package me.centralhardware.forte2firefly.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.centralhardware.forte2firefly.Config
import net.sourceforge.tess4j.Tesseract
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.debug
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO


object OCRService {
    private val tesseract: Tesseract = Tesseract()

    init {
        try {
            Config.tessdataPrefix?.let { tesseract.setDatapath(it) }
            tesseract.setLanguage("eng")

            tesseract.setPageSegMode(6)
            tesseract.setOcrEngineMode(1)

            KSLog.info("Tesseract OCR initialized successfully with language: eng")
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
