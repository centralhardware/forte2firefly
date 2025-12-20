package me.centralhardware.forte2firefly.service

enum class TesseractType {
    SPARSE,      // PSM 11 - для разреженного текста (merchant, общий)
    SINGLE_LINE, // PSM 7 - для одной строки (amount, datetime, card)
    NUMERIC      // PSM 10 - для цифр (transaction number, MCC, foreign amount)
}
