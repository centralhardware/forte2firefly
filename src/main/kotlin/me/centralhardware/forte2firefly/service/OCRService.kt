package me.centralhardware.forte2firefly.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.TesseractException
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * OCR Service для распознавания текста с фотографий используя Tesseract.
 *
 * Требования:
 * 1. Tesseract должен быть установлен в системе
 * 2. Tesseract данные (tessdata) должны быть доступны
 *
 * Установка Tesseract:
 *
 * macOS:
 *   brew install tesseract
 *
 * Ubuntu/Debian:
 *   sudo apt-get install tesseract-ocr
 *
 * Windows:
 *   Скачать установщик: https://github.com/UB-Mannheim/tesseract/wiki
 *
 * Для улучшенного распознавания можно установить дополнительные языки:
 *   brew install tesseract-lang  # macOS
 *   sudo apt-get install tesseract-ocr-eng tesseract-ocr-rus  # Linux
 */
class OCRService(
    private val tessdataPath: String? = null,
    private val language: String = "eng"
) {
    private val logger = LoggerFactory.getLogger(OCRService::class.java)
    private val tesseract: Tesseract = Tesseract()

    init {
        try {
            // Настройка Tesseract
            tessdataPath?.let { tesseract.setDatapath(it) }
            tesseract.setLanguage(language)

            // Настройки для улучшения распознавания
            tesseract.setPageSegMode(6) // Assume a single uniform block of text
            tesseract.setOcrEngineMode(1) // Neural nets LSTM engine only

            logger.info("Tesseract OCR initialized successfully with language: $language")
            if (tessdataPath != null) {
                logger.info("Using tessdata path: $tessdataPath")
            }
        } catch (e: Exception) {
            logger.error("Error initializing Tesseract", e)
            throw IllegalStateException("Failed to initialize Tesseract OCR. Make sure Tesseract is installed.", e)
        }
    }

    /**
     * Распознает текст с фотографии.
     *
     * @param photoBytes байты фотографии
     * @return распознанный текст
     */
    fun recognizeText(photoBytes: ByteArray): String {
        try {
            logger.info("Starting OCR recognition for image (${photoBytes.size} bytes)")

            // Конвертируем байты в BufferedImage
            val inputStream = ByteArrayInputStream(photoBytes)
            val image: BufferedImage = ImageIO.read(inputStream)
                ?: throw IllegalArgumentException("Could not read image from bytes")

            logger.info("Image loaded: ${image.width}x${image.height} pixels")

            // Выполняем OCR
            val result = tesseract.doOCR(image)

            logger.info("OCR completed successfully. Text length: ${result.length} characters")
            logger.debug("OCR result: $result")

            return result.trim()

        } catch (e: TesseractException) {
            logger.error("Tesseract OCR error", e)
            throw RuntimeException("Failed to recognize text from image: ${e.message}", e)
        } catch (e: Exception) {
            logger.error("Error during OCR processing", e)
            throw RuntimeException("Error processing image: ${e.message}", e)
        }
    }

    /**
     * Распознает текст с фотографии с предварительной обработкой изображения.
     * Применяет фильтры для улучшения качества распознавания.
     *
     * @param photoBytes байты фотографии
     * @return распознанный текст
     */
    suspend fun recognizeTextWithPreprocessing(photoBytes: ByteArray): String = withContext(Dispatchers.IO) {
        try {
            logger.info("Starting OCR with preprocessing for image (${photoBytes.size} bytes)")

            val inputStream = ByteArrayInputStream(photoBytes)
            val originalImage: BufferedImage = ImageIO.read(inputStream)
                ?: throw IllegalArgumentException("Could not read image from bytes")

            logger.info("Image loaded, starting preprocessing...")
            // Предварительная обработка изображения для улучшения распознавания
            val processedImage = preprocessImage(originalImage)

            logger.info("Preprocessing complete, running Tesseract OCR...")
            // Выполняем OCR на обработанном изображении в отдельном потоке
            val result = tesseract.doOCR(processedImage)

            logger.info("OCR with preprocessing completed. Text length: ${result.length} characters")
            logger.debug("OCR result: $result")

            result.trim()

        } catch (e: Throwable) {
            logger.error("Error during OCR with preprocessing", e)
            e.printStackTrace()
            // Fallback на обычное распознавание
            logger.info("Falling back to standard OCR")
            recognizeTextBlocking(photoBytes)
        }
    }

    private fun recognizeTextBlocking(photoBytes: ByteArray): String {
        return try {
            recognizeText(photoBytes)
        } catch (e: Exception) {
            logger.error("Even fallback OCR failed", e)
            e.printStackTrace()
            ""
        }
    }

    /**
     * Предварительная обработка изображения для улучшения качества OCR.
     * Применяет фильтры повышения контрастности и резкости.
     */
    private fun preprocessImage(image: BufferedImage): BufferedImage {
        logger.info("Preprocessing image: ${image.width}x${image.height}")

        // 1. Увеличиваем разрешение изображения умеренно (1.5x достаточно)
        val scaledImage = scaleImage(image, 1.5)

        // 2. Конвертируем в grayscale (черно-белое)
        val grayImage = convertToGrayscale(scaledImage)

        // 3. Умеренно увеличиваем контрастность
        val contrastedImage = increaseContrast(grayImage, 1.3f)

        // 4. Применяем простой threshold (более надежный чем адаптивный)
        val binarizedImage = applyThreshold(contrastedImage, 128)

        logger.info("Preprocessing complete")
        return binarizedImage
    }

    /**
     * Увеличивает разрешение изображения (upscaling)
     */
    private fun scaleImage(image: BufferedImage, scale: Double): BufferedImage {
        val newWidth = (image.width * scale).toInt()
        val newHeight = (image.height * scale).toInt()
        val scaledImage = BufferedImage(newWidth, newHeight, image.type)
        val graphics = scaledImage.createGraphics()

        // Используем высококачественное масштабирование
        graphics.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC
        )
        graphics.setRenderingHint(
            java.awt.RenderingHints.KEY_RENDERING,
            java.awt.RenderingHints.VALUE_RENDER_QUALITY
        )
        graphics.setRenderingHint(
            java.awt.RenderingHints.KEY_ANTIALIASING,
            java.awt.RenderingHints.VALUE_ANTIALIAS_ON
        )

        graphics.drawImage(image, 0, 0, newWidth, newHeight, null)
        graphics.dispose()
        return scaledImage
    }

    /**
     * Конвертирует изображение в grayscale (оттенки серого)
     */
    private fun convertToGrayscale(image: BufferedImage): BufferedImage {
        val grayImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
        val graphics = grayImage.createGraphics()
        graphics.drawImage(image, 0, 0, null)
        graphics.dispose()
        return grayImage
    }

    /**
     * Увеличивает контрастность изображения
     */
    private fun increaseContrast(image: BufferedImage, factor: Float): BufferedImage {
        val result = BufferedImage(image.width, image.height, image.type)

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF

                // Применяем контрастность
                val newR = ((r - 128) * factor + 128).toInt().coerceIn(0, 255)
                val newG = ((g - 128) * factor + 128).toInt().coerceIn(0, 255)
                val newB = ((b - 128) * factor + 128).toInt().coerceIn(0, 255)

                val newRgb = (newR shl 16) or (newG shl 8) or newB
                result.setRGB(x, y, newRgb)
            }
        }

        return result
    }

    /**
     * Применяет threshold (бинаризация) - все пиксели ярче threshold становятся белыми,
     * все темнее - черными. Это улучшает распознавание текста.
     */
    private fun applyThreshold(image: BufferedImage, threshold: Int): BufferedImage {
        val result = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_BINARY)

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF

                // Вычисляем яркость (grayscale)
                val brightness = (r + g + b) / 3

                // Применяем threshold
                val newRgb = if (brightness > threshold) 0xFFFFFF else 0x000000
                result.setRGB(x, y, newRgb)
            }
        }

        return result
    }

}
