package me.centralhardware.forte2firefly.service

import com.clickhouse.jdbc.ClickHouseDataSource
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.debug
import dev.inmo.kslog.common.info
import kotliquery.queryOf
import kotliquery.sessionOf
import me.centralhardware.forte2firefly.Config
import java.util.*
import javax.sql.DataSource

object DatabaseService {

    private val dataSource: DataSource by lazy {
        val props = Properties().apply {
            Config.clickhouseUser?.let { put("user", it) }
            Config.clickhousePassword?.let { put("password", it) }
        }

        ClickHouseDataSource(Config.clickhouseUrl, props)
    }

    fun getCurrentCountry(): String? {
        val query = """
            SELECT country
            FROM ${Config.clickhouseDatabase}.country_days_tracker
            ORDER BY date_time DESC
            LIMIT 1
        """.trimIndent()

        KSLog.debug("Querying ClickHouse for current country: $query")

        return sessionOf(dataSource).use { session ->
            session.run(
                queryOf(query).map { row ->
                    row.string("country")
                }.asSingle
            )
        }?.also { country ->
            KSLog.info("Current country from ClickHouse: $country")
        } ?: run {
            KSLog.info("No country data found in ClickHouse")
            null
        }
    }
}
