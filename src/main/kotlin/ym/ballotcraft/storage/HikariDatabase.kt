package ym.ballotcraft.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ym.ballotcraft.BallotCraftConfig
import java.sql.Connection

class HikariDatabase(private val settings: BallotCraftConfig.DatabaseSettings) {
    private lateinit var dataSource: HikariDataSource

    @Synchronized
    fun start() {
        if (::dataSource.isInitialized) {
            return
        }
        val config = HikariConfig().apply {
            jdbcUrl = settings.jdbcUrl
            username = settings.username
            password = settings.password
            maximumPoolSize = settings.poolSize
            connectionTimeout = settings.connectionTimeoutMillis
            poolName = "BallotCraft-Hikari"
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        }
        dataSource = HikariDataSource(config)
    }

    fun connection(): Connection = dataSource.connection

    fun close() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    val tablePrefix: String
        get() = settings.tablePrefix
}
