package com.gatekeeper

import com.gatekeeper.admin.configureProjectAdminRoutes
import com.gatekeeper.auth.configureAuthRoutes
import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.tables.Users
import com.gatekeeper.gate.configureGateRoutes
import com.gatekeeper.paystack.PaystackClient
import com.gatekeeper.paystack.configurePaystackWebhookRoutes
import com.gatekeeper.plugins.configureDatabase
import com.gatekeeper.plugins.configureMonitoring
import com.gatekeeper.plugins.configureRedis
import com.gatekeeper.plugins.configureRouting
import com.gatekeeper.plugins.configureSecurity
import com.gatekeeper.plugins.configureSerialization
import com.gatekeeper.scheduler.AutoBlockerJob
import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    AppConfig.logConfig()

    configureSerialization()
    configureMonitoring()
    configureDatabase()
    configureRedis()
    configureSecurity()
    configureRouting()
    configureGateRoutes()
    configureAuthRoutes()
    configureProjectAdminRoutes()
    configurePaystackWebhookRoutes()

    seedDefaultAdmin()

    val appScope = CoroutineScope(SupervisorJob())
    AutoBlockerJob.start(appScope)

    monitor.subscribe(ApplicationStopping) {
        runCatching { PaystackClient.close() }
    }
}

private fun seedDefaultAdmin() {
    val logger = LoggerFactory.getLogger("com.gatekeeper.Application")
    try {
        val count: Long = transaction {
            Users.selectAll().count()
        }
        if (count == 0L) {
            val defaultEmail = "admin@gatekeeper.local"
            val defaultPassword = "admin123"
            val hash = BCrypt.hashpw(defaultPassword, BCrypt.gensalt(12))
            transaction {
                com.gatekeeper.db.tables.Users.insert { stmt ->
                    stmt[Users.email] = defaultEmail
                    stmt[Users.passwordHash] = hash
                    stmt[Users.role] = "admin"
                }
            }
            logger.warn("=================================================================")
            logger.warn("DEFAULT ADMIN USER CREATED — change this immediately!")
            logger.warn("Email:    $defaultEmail")
            logger.warn("Password: $defaultPassword")
            logger.warn("=================================================================")
        }
    } catch (e: Exception) {
        logger.error("Failed to seed default admin user", e)
    }
}
