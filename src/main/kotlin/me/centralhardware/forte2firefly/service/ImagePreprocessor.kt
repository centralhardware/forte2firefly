package me.centralhardware.forte2firefly.service

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.debug
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

object ImagePreprocessor {

    fun preprocessImage(image: BufferedImage): BufferedImage {
        val cropTopPercent = 0.10
        val cropY = (image.height * cropTopPercent).toInt()
        val croppedHeight = image.height - cropY

        val croppedImage = image.getSubimage(0, cropY, image.width, croppedHeight)

        val scaledWidth = croppedImage.width * 2
        val scaledHeight = croppedImage.height * 2

        val scaled = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB)
        val g2d = scaled.createGraphics()
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.drawImage(croppedImage, 0, 0, scaledWidth, scaledHeight, null)
        } finally {
            g2d.dispose()
        }

        return scaled
    }

    fun invertColors(image: BufferedImage): BufferedImage {
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

    fun binarizeImage(image: BufferedImage, threshold: Int = 128): BufferedImage {
        val binary = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)

        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                val gray = (r + g + b) / 3

                val binaryValue = if (gray > threshold) 255 else 0
                val binaryRgb = (binaryValue shl 16) or (binaryValue shl 8) or binaryValue
                binary.setRGB(x, y, binaryRgb)
            }
        }

        return binary
    }

    fun extractAndProcessRegion(
        image: BufferedImage,
        region: ReceiptRegionConfig.Region,
        invert: Boolean = true,
        debugMode: Boolean = false,
        debugName: String = "region"
    ): BufferedImage? {
        return try {
            val startY = region.startY
            val endY = region.endY
            val startX = region.startX
            val endX = if (region.endX == -1) image.width else region.endX

            val regionHeight = endY - startY
            val regionWidth = endX - startX

            if (regionHeight <= 0 || regionWidth <= 0) {
                KSLog.info("Invalid region dimensions for $debugName: ${regionWidth}x$regionHeight")
                return null
            }

            KSLog.debug("Extracting $debugName region: X=$startX-$endX, Y=$startY-$endY (${regionWidth}x${regionHeight}px)")

            val region = image.getSubimage(startX, startY, regionWidth, regionHeight)

            val result = if (invert) invertColors(region) else region

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
}
