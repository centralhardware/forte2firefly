package me.centralhardware.forte2firefly.service

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.error
import dev.inmo.kslog.common.info
import me.centralhardware.forte2firefly.Config
import net.sourceforge.tess4j.Tesseract

object TesseractFactory {

    fun create(
        name: String,
        pageSegMode: Int,
        ocrEngineMode: Int = 1,
        language: String = "eng",
        whitelist: String? = null,
        additionalVariables: Map<String, String> = emptyMap()
    ): Tesseract {
        return Tesseract().apply {
            try {
                setDatapath(Config.tessdataPrefix)
                if (language.isNotEmpty()) {
                    setLanguage(language)
                }
                setPageSegMode(pageSegMode)
                setOcrEngineMode(ocrEngineMode)

                setVariable("load_system_dawg", "false")
                setVariable("load_freq_dawg", "false")
                setVariable("user_defined_dpi", "300")

                whitelist?.let { setVariable("tessedit_char_whitelist", it) }

                additionalVariables.forEach { (key, value) ->
                    setVariable(key, value)
                }

                KSLog.info("$name Tesseract OCR initialized (PSM $pageSegMode, OEM $ocrEngineMode)")
            } catch (e: Exception) {
                KSLog.error("Error initializing $name Tesseract", e)
                throw IllegalStateException("Failed to initialize $name Tesseract OCR.", e)
            }
        }
    }
}
