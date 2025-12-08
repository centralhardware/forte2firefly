package me.centralhardware.forte2firefly

import me.centralhardware.forte2firefly.service.ImagePreprocessor
import me.centralhardware.forte2firefly.service.ReceiptRegionConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.awt.image.BufferedImage

class ImagePreprocessorTest {

    @Test
    fun `test preprocessImage does not crash`() {
        // Create a simple test image
        val testImage = BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)

        // Should not crash
        val result = ImagePreprocessor.preprocessImage(testImage)

        assertNotNull(result)
        assertTrue(result.width > 0)
        assertTrue(result.height > 0)
    }

    @Test
    fun `test invertColors with RGB image`() {
        val testImage = BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB)

        val result = ImagePreprocessor.invertColors(testImage)

        assertNotNull(result)
        assertEquals(50, result.width)
        assertEquals(50, result.height)
    }

    @Test
    fun `test extractAndProcessRegion with valid region`() {
        val testImage = BufferedImage(1000, 2000, BufferedImage.TYPE_INT_RGB)

        val testRegion = ReceiptRegionConfig.Region(
            startY = 100,
            endY = 200,
            startX = 50,
            endX = 150
        )

        val result = ImagePreprocessor.extractAndProcessRegion(
            image = testImage,
            region = testRegion,
            invert = true,
            debugMode = false,
            debugName = "test_region"
        )

        assertNotNull(result)
        assertEquals(100, result!!.width) // 150 - 50
        assertEquals(100, result.height) // 200 - 100
    }
}
