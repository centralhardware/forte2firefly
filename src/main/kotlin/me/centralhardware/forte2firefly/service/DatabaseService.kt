package me.centralhardware.forte2firefly.service

import com.clickhouse.client.ClickHouseClient
import com.clickhouse.client.ClickHouseCredentials
import com.clickhouse.client.ClickHouseNode
import com.clickhouse.client.ClickHouseProtocol
import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.debug
import dev.inmo.kslog.common.info
import me.centralhardware.forte2firefly.Config

object DatabaseService {

    private fun createClient(): ClickHouseClient {
        return ClickHouseClient.newInstance(ClickHouseProtocol.HTTP)
    }

    private fun createNode(): ClickHouseNode {
        val url = Config.clickhouseUrl.removePrefix("http://").removePrefix("https://")
        val parts = url.split(":")
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 8123

        val builder = ClickHouseNode.builder()
            .host(host)
            .port(ClickHouseProtocol.HTTP, port)
            .database(Config.clickhouseDatabase)

        if (Config.clickhouseUser != null && Config.clickhousePassword != null) {
            builder.credentials(ClickHouseCredentials.fromUserAndPassword(
                Config.clickhouseUser,
                Config.clickhousePassword
            ))
        }

        return builder.build()
    }

    fun getCurrentCountry(): String? {
        val query = """
            SELECT country
            FROM country_days_tracker_bot.country_days_tracker
            ORDER BY date_time DESC
            LIMIT 1
        """.trimIndent()

        KSLog.debug("Querying ClickHouse for current country: $query")

        val client = createClient()
        val node = createNode()

        return client.read(node).query(query).executeAndWait().use { response ->
            response.firstRecord()?.let { record ->
                val country = record.getValue(0).asString()
                KSLog.info("Current country from ClickHouse: $country")
                country
            } ?: run {
                KSLog.info("No country data found in ClickHouse")
                null
            }
        }
    }
}
