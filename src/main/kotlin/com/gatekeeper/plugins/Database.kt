package com.gatekeeper.plugins

import com.gatekeeper.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.gatekeeper.plugins.Database")

object DatabaseFactory {
    lateinit var dataSource: HikariDataSource
        private set

    fun init(url: String, user: String, password: String, driver: String = "org.postgresql.Driver") {
        val config = HikariConfig().apply {
            this.driverClassName = driver
            this.jdbcUrl = url
            this.username = user
            this.password = password
            maximumPoolSize = 10
            minimumIdle = 2
            idleTimeout = 30000
            connectionTimeout = 5000
            validationTimeout = 3000
        }
        dataSource = HikariDataSource(config)
        Database.connect(dataSource)
        DatabaseMigrations.validate(url, user, password)

        logger.info("Database connected: $url")
    }

    fun close() {
        if (::dataSource.isInitialized) {
            dataSource.close()
            logger.info("Database connection pool closed")
        }
    }

    fun isHealthy(): Boolean {
        return try {
            dataSource.connection.use { connection ->
                connection.isValid(3)
            }
        } catch (_: Exception) {
            false
        }
    }
}

fun Application.configureDatabase() {
    DatabaseFactory.init(AppConfig.dbUrl, AppConfig.dbUser, AppConfig.dbPassword)

    environment.monitor.subscribe(ApplicationStopping) {
        DatabaseFactory.close()
    }
}
