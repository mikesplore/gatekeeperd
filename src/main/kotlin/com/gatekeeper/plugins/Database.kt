package com.gatekeeper.plugins

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.tables.AuditLog
import com.gatekeeper.db.tables.PaymentEvents
import com.gatekeeper.db.tables.Payments
import com.gatekeeper.db.tables.Projects
import com.gatekeeper.db.tables.Users
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
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
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Users, Projects, Payments, PaymentEvents, AuditLog)
            exec("UPDATE payments SET gateway_status = status WHERE gateway_status != status OR gateway_status IS NULL")
        }

        logger.info("Database connected: $url")
    }

    fun close() {
        if (::dataSource.isInitialized) {
            dataSource.close()
            logger.info("Database connection pool closed")
        }
    }
}

fun Application.configureDatabase() {
    DatabaseFactory.init(AppConfig.dbUrl, AppConfig.dbUser, AppConfig.dbPassword)

    environment.monitor.subscribe(ApplicationStopping) {
        DatabaseFactory.close()
    }
}