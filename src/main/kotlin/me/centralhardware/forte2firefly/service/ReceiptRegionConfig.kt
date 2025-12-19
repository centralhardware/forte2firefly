package me.centralhardware.forte2firefly.service

object ReceiptRegionConfig {

    data class Region(
        val startY: Int,
        val endY: Int,
        val startX: Int = 0,
        val endX: Int = -1
    )

    val MERCHANT = Region(startY = 586, endY = 742)

    val AMOUNT = Region(startY = 714, endY = 846)

    val DATE_TIME = Region(startY = 1284, endY = 1396)

    val CARD = Region(startY = 1485, endY = 1581)

    val TRANSACTION_NUMBER = Region(startY = 1661, endY = 1769)

    val FOREIGN_AMOUNT = Region(startY = 1850, endY = 1954, startX = 25, endX = 200)

    val MCC_NEW_FORMAT = Region(startY = 960, endY = 1010, startX = 25, endX = 200)

    val MCC_OLD_WITH_FOREIGN = Region(startY = 2065, endY = 2105, startX = 25, endX = 200)

    val MCC_OLD_WITHOUT_FOREIGN = Region(startY = 1950, endY = 1990, startX = 25, endX = 200)

    fun getMccOldFormatRegion(hasForeignAmount: Boolean): Region {
        return if (hasForeignAmount) MCC_OLD_WITH_FOREIGN else MCC_OLD_WITHOUT_FOREIGN
    }
}
