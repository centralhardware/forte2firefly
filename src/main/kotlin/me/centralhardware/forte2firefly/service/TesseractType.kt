package me.centralhardware.forte2firefly.service

enum class TesseractType(val pageSegMode: Int) {
    SPARSE(11),
    SINGLE_LINE(7),
    NUMERIC(10),
    NUMERIC_LINE(7)
}
