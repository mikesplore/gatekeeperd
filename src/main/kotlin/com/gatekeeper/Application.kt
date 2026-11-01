package com.gatekeeper

import com.gatekeeper.admin.*
import com.gatekeeper.feature.nginx.presentation.configureNginxRoutes
import com.gatekeeper.api.InputValidators
import com.gatekeeper.auth.configureAuthRoutes
import com.gatekeeper.config.AppSettings
import com.gatekeeper.security.SecretValueCipher
import com.gatekeeper.customer.configureCustomerRoutes
import com.gatekeeper.db.tables.Users
import com.gatekeeper.db.ServiceIdentityBackfill
import com.gatekeeper.deployment.DeploymentWorker
import com.gatekeeper.integrations.GitHubAppClient
import com.gatekeeper.integrations.ScribedIntegrationClient
import com.gatekeeper.gate.configureGateRoutes
import com.gatekeeper.integrations.configureGitHubAdminRoutes
import com.gatekeeper.integrations.configureGitHubWebhookRoutes
import com.gatekeeper.feature.payment.data.provider.MpesaGateway
import com.gatekeeper.feature.payment.data.provider.PaystackClient
import com.gatekeeper.feature.payment.presentation.configureMpesaRoutes
import com.gatekeeper.feature.payment.presentation.configurePaystackWebhookRoutes
import com.gatekeeper.feature.payment.presentation.configurePaymentAdminRoutes
import com.gatekeeper.admin.configureLogAdminRoutes
import com.gatekeeper.feature.payment.presentation.configurePaymentRoutes
import com.gatekeeper.feature.nginx.domain.service.NginxBackfillRunner
import com.gatekeeper.plugins.*
import com.gatekeeper.feature.payment.domain.usecase.ReconcilePayments
import com.gatekeeper.feature.payment.domain.repository.PaymentRepository as PaymentDomainRepository
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import org.koin.ktor.ext.get
import com.gatekeeper.docker.DockerGatewayFactory
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.feature.nginx.application.InspectSiteConfigUseCase
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.infrastructure.ProcessRunnerImpl
import com.gatekeeper.scheduler.AutoBlockerJob
import com.gatekeeper.scheduler.IntegrationOutboxJob
import com.gatekeeper.scheduler.ReconciliationJob
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val command = args.firstOrNull()?.takeIf { !it.startsWith("-") } ?: "serve"
    if (command != "serve") {
        operationalCommand(command, args.drop(1).toTypedArray())
        return
    }
    val settings = AppSettings.fromEnv()
    Telemetry.init(settings.telemetry)
    val port = args.portArg() ?: settings["PORT"]?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

private fun operationalCommand(command: String, args: Array<String>) {
    val settings = AppSettings.fromEnv()
    SecretValueCipher.configure(settings.application)
    val db = settings.db
    when (command) {
        "migrate" -> if (args.firstOrNull() == "baseline") {
            DatabaseMigrations.baseline(
                db.url, db.user, db.password, db.migrationBaselineVersion
            )
        } else {
            DatabaseMigrations.migrate(
                db.url, db.user, db.password, db.migrationBaselineVersion
            )
        }

        "backfill" -> {
            DatabaseFactory.init(
                db.url, db.user, db.password, db.migrationBaselineVersion
            ); try {
                val docker = DockerGatewayFactory.create(settings.docker, ProcessRunnerImpl())
                try {
                    NginxBackfillRunner.create(settings).run(
                        args.firstOrNull { !it.startsWith("--") } ?: settings.nginx.sitesAvailablePath,
                        args.contains("--dry-run"),
                        docker,
                        settings
                    )
                } finally {
                    docker.close()
                }
            } finally {
                DatabaseFactory.close()
            }
        }

        "service-backfill" -> {
            require(args.contains("--dry-run")) {
                "service-backfill currently supports report-only mode; pass --dry-run"
            }
            val report = ServiceIdentityBackfill.report(
                db.url, db.user, db.password
            )
            println("planned_default_services=${report.projectsWithoutDefaultService}")
        }

        "health-check" -> {
            DatabaseFactory.init(
                db.url, db.user, db.password, db.migrationBaselineVersion
            ); RedisService.init(settings.redis.host, settings.redis.port)
            val ready =
                DatabaseFactory.isHealthy() && RedisService.isHealthy() && DatabaseFactory.migrationsHealthy(); RedisService.close(); DatabaseFactory.close(); if (!ready) exitProcess(
                1
            ); println("ready")
        }

        else -> error("Unknown command '$command'. Use serve, migrate, backfill, service-backfill, or health-check.")
    }
}

private fun Array<String>.portArg(): Int? =
    firstOrNull { it.startsWith("-port=") }?.substringAfter("=")?.toIntOrNull()

fun Application.module() {
    configureDependencyInjection()
    val appJob = SupervisorJob()
    val appScope = CoroutineScope(appJob + Dispatchers.Default)
    // Register before configureDatabase/configureRedis so background jobs stop
    // before those plugins close their connection pools during ApplicationStopping.
    monitor.subscribe(ApplicationStopping) {
        runBlocking { appJob.cancelAndJoin() }
    }

    val dockerService: DockerGateway = get<DockerGateway>()
    val inspectSiteConfigUseCase = get<InspectSiteConfigUseCase>()
    val serviceRepository = get<ServiceRepository>()
    val projectRepository = get<ProjectRepository>()
    val siteRepository = get<SiteRepository>()
    val autoBlockerJob = get<AutoBlockerJob>()

    configureSerialization()
    configureMonitoring()
    configureApiDocumentation()
    val settings = get<AppSettings>()
    SecretValueCipher.configure(settings.application)
    configureDatabase()
    if (settings.nginx.deploymentUpstreamsEnabled) {
        inspectSiteConfigUseCase.bootstrapDeploymentUpstreams()
    } else {
        org.slf4j.LoggerFactory.getLogger("com.gatekeeper.Application")
            .warn("Nginx deployment upstream bootstrap is disabled; deployment cutovers require nginx configuration")
    }
    configureRedis()
    configureSecurity()
    routing {
        configureNginxRoutes()
    }
    configureRouting()
    configureGateRoutes()
    configurePaymentRoutes()
    configureCustomerRoutes()
    configureAuthRoutes()
    configureProjectAdminRoutes()
    configureProjectSetupAdminRoutes()
    configureServiceAdminRoutes()
    configureOperationsAdminRoutes()
    configureIntegrationAdminRoutes()
    configurePaymentAdminRoutes()
    configureDeploymentAdminRoutes(dockerService, projectRepository, siteRepository, inspectSiteConfigUseCase, get())
    configureSystemAdminRoutes()
    configureLogAdminRoutes()
    configureRegistryAdminRoutes()
    configurePaystackWebhookRoutes()
    configureMpesaRoutes()
    configureGitHubWebhookRoutes()
    configureGitHubAdminRoutes()

    seedInitialAdmin(settings.application)

    autoBlockerJob.start(appScope, settings.scheduler)
    ReconciliationJob.start(appScope, get<ReconcilePayments>(), settings.scheduler)
    IntegrationOutboxJob.start(appScope, get(), get<PaymentDomainRepository>(), get<ProjectBalanceAdapter>(), get<ScribedIntegrationClient>())
    get<DeploymentWorker>().start(appScope)

    monitor.subscribe(ApplicationStopping) {
        runCatching { get<GitHubAppClient>().close() }
        runCatching { get<DockerGateway>().close() }
        runCatching { get<PaystackClient>().close() }
        runCatching { get<MpesaGateway>().close() }
        runCatching { org.koin.core.context.stopKoin() }
        runCatching { Telemetry.close() }
    }
}

private fun seedInitialAdmin(applicationSettings: AppSettings.ApplicationSettings) {
    val logger = LoggerFactory.getLogger("com.gatekeeper.Application")
    try {
        val count: Long = transaction {
            Users.selectAll().count()
        }
        if (count > 0L) {
            return
        }

        val email = applicationSettings.adminEmail.trim().lowercase()
        val password = applicationSettings.adminPassword

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
