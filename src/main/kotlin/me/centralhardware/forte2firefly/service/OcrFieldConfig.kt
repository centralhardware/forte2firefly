package me.centralhardware.forte2firefly.service

sealed class OcrFieldConfig(
    val name: String,
    val region: ReceiptRegionConfig.Region,
    val tesseractType: TesseractType,
    val invert: Boolean = true,
    val binarize: Boolean = false,
    val binarizeThreshold: Int = 128
) {
    data object Merchant : OcrFieldConfig(
        name = "merchant",
        region = ReceiptRegionConfig.MERCHANT,
        tesseractType = TesseractType.SPARSE
    )

    data object Amount : OcrFieldConfig(
        name = "amount",
        region = ReceiptRegionConfig.AMOUNT,
        tesseractType = TesseractType.SINGLE_LINE
    )

    data object DateTime : OcrFieldConfig(
        name = "datetime",
        region = ReceiptRegionConfig.DATE_TIME,
        tesseractType = TesseractType.SINGLE_LINE
    )

    data object Card : OcrFieldConfig(
        name = "card",
        region = ReceiptRegionConfig.CARD,
        tesseractType = TesseractType.SINGLE_LINE
    )

    data object TransactionNumber : OcrFieldConfig(
        name = "transaction_number",
        region = ReceiptRegionConfig.TRANSACTION_NUMBER,
        tesseractType = TesseractType.NUMERIC,
        binarize = true,
        binarizeThreshold = 128
    )

    data object ForeignAmount : OcrFieldConfig(
        name = "foreign_amount",
        region = ReceiptRegionConfig.FOREIGN_AMOUNT,
        tesseractType = TesseractType.NUMERIC,
        invert = false,
        binarize = true,
        binarizeThreshold = 150
    )

    data object MccNewFormat : OcrFieldConfig(
        name = "mcc_new_format",
        region = ReceiptRegionConfig.MCC_NEW_FORMAT,
        tesseractType = TesseractType.SPARSE
    )

    class MccOldFormat(hasForeignAmount: Boolean) : OcrFieldConfig(
        name = "mcc",
        region = ReceiptRegionConfig.getMccOldFormatRegion(hasForeignAmount),
        tesseractType = TesseractType.NUMERIC,
        binarize = true,
        binarizeThreshold = 150
    )
}
