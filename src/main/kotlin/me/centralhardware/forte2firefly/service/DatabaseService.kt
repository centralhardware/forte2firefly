package me.centralhardware.forte2firefly.service

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.debug
import dev.inmo.kslog.common.info
import kotliquery.queryOf
import kotliquery.sessionOf
import me.centralhardware.forte2firefly.Config
import javax.sql.DataSource

object DatabaseService {

    private val dataSource: DataSource by lazy {
        HikariConfig().apply {
            jdbcUrl = Config.clickhouseUrl
            maximumPoolSize = 5
            minimumIdle = 1
            connectionTimeout = 10000
        }.let { HikariDataSource(it) }
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
