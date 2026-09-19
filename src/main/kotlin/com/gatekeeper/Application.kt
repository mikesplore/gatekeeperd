package com.gatekeeper

import com.gatekeeper.api.InputValidators
import com.gatekeeper.admin.configureNginxAdminRoutes
import com.gatekeeper.admin.configurePaymentAdminRoutes
import com.gatekeeper.admin.configureProjectAdminRoutes
import com.gatekeeper.admin.configureOperationsAdminRoutes
import com.gatekeeper.auth.configureAuthRoutes
import com.gatekeeper.customer.configureCustomerRoutes
import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.tables.Users
import com.gatekeeper.gate.configureGateRoutes
import com.gatekeeper.paystack.PaystackClient
import com.gatekeeper.paystack.PaystackProviderClient
import com.gatekeeper.paystack.configurePaystackWebhookRoutes
import com.gatekeeper.payments.PaymentReconciliationService
import com.gatekeeper.mpesa.MpesaClient
import com.gatekeeper.mpesa.configureMpesaRoutes
import com.gatekeeper.plugins.configureDatabase
import com.gatekeeper.plugins.configureMonitoring
import com.gatekeeper.plugins.configureRedis
import com.gatekeeper.plugins.configureRouting
import com.gatekeeper.plugins.configureSecurity
import com.gatekeeper.plugins.configureSerialization
import com.gatekeeper.scheduler.AutoBlockerJob
import com.gatekeeper.scheduler.ReconciliationJob
import com.gatekeeper.scheduler.IntegrationOutboxJob
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    val port = args.portArg() ?: System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

private fun Array<String>.portArg(): Int? =
    firstOrNull { it.startsWith("-port=") }?.substringAfter("=")?.toIntOrNull()

fun Application.module() {
    AppConfig.logConfig()

    configureSerialization()
    configureMonitoring()
    configureDatabase()
    configureRedis()
    configureSecurity()
    configureRouting()
    configureGateRoutes()
    configureCustomerRoutes()
    configureAuthRoutes()
    configureProjectAdminRoutes()
    configureOperationsAdminRoutes()
    configurePaymentAdminRoutes()
    configureNginxAdminRoutes()
    configurePaystackWebhookRoutes()
    configureMpesaRoutes()
    PaymentReconciliationService.register(PaystackProviderClient())
    PaymentReconciliationService.register(MpesaClient)

    seedInitialAdmin()

    val appScope = CoroutineScope(SupervisorJob())
    AutoBlockerJob.start(appScope)
    ReconciliationJob.start(appScope)
    IntegrationOutboxJob.start(appScope)

    monitor.subscribe(ApplicationStopping) {
        runCatching { PaystackClient.close() }
        runCatching { MpesaClient.close() }
    }
}

private fun seedInitialAdmin() {
    val logger = LoggerFactory.getLogger("com.gatekeeper.Application")
    try {
        val count: Long = transaction {
            Users.selectAll().count()
        }
        if (count > 0L) {
            return
        }

        val email = AppConfig.adminEmail.trim().lowercase()
        val password = AppConfig.adminPassword

        if (email.isBlank() || password.isBlank()) {
            logger.error(
                "No admin users in database and ADMIN_EMAIL/ADMIN_PASSWORD are not set. " +
                    "Set both in .env and restart, or insert a user manually."
            )
            return
        }

        if (!InputValidators.isValidEmail(email)) {
            logger.error("ADMIN_EMAIL is not a valid email address — admin user was not created")
            return
        }

        if (password.length < 8) {
            logger.error("ADMIN_PASSWORD must be at least 8 characters — admin user was not created")
            return
        }

        val hash = BCrypt.hashpw(password, BCrypt.gensalt(12))
        transaction {
            Users.insert { stmt ->
                stmt[Users.email] = email
                stmt[Users.passwordHash] = hash
                stmt[Users.role] = "admin"
            }
        }
        logger.info("Initial admin user created for {}", email)
    } catch (e: Exception) {
        logger.error("Failed to seed initial admin user", e)
    }
}
