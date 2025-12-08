package me.centralhardware.forte2firefly.service

/**
 * Конфигурация регионов чека Forte для OCR распознавания
 * Все координаты указаны в пикселях после preprocessing (обрезка 10% сверху и увеличение в 2x)
 */
object ReceiptRegionConfig {

    data class Region(
        val startY: Int,
        val endY: Int,
        val startX: Int = 0,
        val endX: Int = -1 // -1 означает "до конца изображения"
    )

    /**
     * Регион с названием торговой точки (merchant name)
     * Координаты получены из Label Studio markup
     */
    val MERCHANT = Region(startY = 586, endY = 742)

    /**
     * Регион с суммой транзакции и валютой
     * Координаты получены из Label Studio markup
     */
    val AMOUNT = Region(startY = 714, endY = 846)

    /**
     * Регион с датой и временем транзакции
     * Координаты получены из Label Studio markup
     */
    val DATE_TIME = Region(startY = 1284, endY = 1396)

    /**
     * Регион с номером карты
     * Координаты получены из Label Studio markup
     */
    val CARD = Region(startY = 1485, endY = 1581)

    /**
     * Регион с номером транзакции
     * Координаты получены из Label Studio markup
     */
    val TRANSACTION_NUMBER = Region(startY = 1661, endY = 1769)

    /**
     * Регион с суммой в другой валюте (foreign amount) или MCC кодом
     * Ограничен по X: 25-200px для более точного распознавания
     */
    val FOREIGN_AMOUNT = Region(startY = 1850, endY = 1954, startX = 25, endX = 200)

    /**
     * Регион с MCC кодом в новом формате (после transaction number)
     * Ограничен по X: 25-200px
     */
    val MCC_NEW_FORMAT = Region(startY = 960, endY = 1010, startX = 25, endX = 200)

    /**
     * Регион с MCC кодом в старом формате (когда есть foreign amount)
     * Ограничен по X: 25-200px
     */
    val MCC_OLD_WITH_FOREIGN = Region(startY = 2065, endY = 2105, startX = 25, endX = 200)

    /**
     * Регион с MCC кодом в старом формате (когда нет foreign amount)
     * Ограничен по X: 25-200px
     */
    val MCC_OLD_WITHOUT_FOREIGN = Region(startY = 1950, endY = 1990, startX = 25, endX = 200)

    /**
     * Получить регион для MCC кода в зависимости от наличия foreign amount
     */
    fun getMccOldFormatRegion(hasForeignAmount: Boolean): Region {
        return if (hasForeignAmount) MCC_OLD_WITH_FOREIGN else MCC_OLD_WITHOUT_FOREIGN
    }
}
