package com.gatekeeper.config

// Centralized application settings loaded from the process environment or local .env file.

import io.github.cdimascio.dotenv.dotenv
import org.slf4j.LoggerFactory

object AppConfig {
    private val logger = LoggerFactory.getLogger(AppConfig::class.java)

    private val dotenv by lazy {
        dotenv {
            filename = ".env"
            ignoreIfMissing = true
        }
    }

    private fun requiredSetting(key: String): String {
        val value = System.getenv(key)?.takeIf { it.isNotBlank() }
            ?: dotenv[key]?.takeIf { it.isNotBlank() }
        require(!value.isNullOrBlank()) {
            "Missing required configuration '$key'. Set it in the environment or .env file."
        }
        return value
    }

    private fun optionalSetting(key: String, default: String): String =
        System.getenv(key)?.takeIf { it.isNotBlank() }
            ?: dotenv[key]?.takeIf { it.isNotBlank() }
            ?: default

    // Database — no hardcoded credentials; must come from env / .env
    val dbUrl: String = requiredSetting("DB_URL")
    val dbUser: String = requiredSetting("DB_USER")
    val dbPassword: String = requiredSetting("DB_PASSWORD")
    val dbMigrationBaselineVersion: String = optionalSetting("DB_MIGRATION_BASELINE_VERSION", "0")

    // Redis
    val redisHost: String = requiredSetting("REDIS_HOST")
    val redisPort: Int = optionalSetting("REDIS_PORT", "6379").toInt()

    // JWT Auth
    val jwtSecret: String = requiredSetting("JWT_SECRET")
    val jwtIssuer: String = optionalSetting("JWT_ISSUER", "gatekeeperd")
    val jwtAudience: String = optionalSetting("JWT_AUDIENCE", "gatekeeperd-admin")

    // Paystack
    val paystackSecretKey: String = optionalSetting("PAYSTACK_SECRET_KEY", "")
    val paystackPublicKey: String = optionalSetting("PAYSTACK_PUBLIC_KEY", "")

    // M-Pesa Daraja
    val mpesaConsumerKey: String = optionalSetting("MPESA_CONSUMER_KEY", "")
    val mpesaConsumerSecret: String = optionalSetting("MPESA_CONSUMER_SECRET", "")
    val mpesaShortCode: String = optionalSetting("MPESA_SHORT_CODE", "")
    val mpesaPasskey: String = optionalSetting("MPESA_PASSKEY", "")
    val mpesaCallbackUrl: String = optionalSetting("MPESA_CALLBACK_URL", "")
    val mpesaEnvironment: String = optionalSetting("MPESA_ENVIRONMENT", "sandbox")

    /** Public URL of gatekeeperd (e.g. https://gateapi.mikesplore.me) — used for Paystack callbacks */
    val publicBaseUrl: String = optionalSetting("GATEKEEPER_PUBLIC_URL", "")

    /** Frontend URL used for customer portal, receipt, payment-success, and support links. */
    val frontendBaseUrl: String = optionalSetting("GATEKEEPER_FRONTEND_URL", "")

    val supportContactEmail: String = optionalSetting("SUPPORT_CONTACT_EMAIL", "support@gatekeeper.local")
    val resendApiKey: String = optionalSetting("RESEND_API_KEY", "")
    val resendFromEmail: String = optionalSetting("RESEND_FROM_EMAIL", "")
    val passwordResetUrl: String = optionalSetting("PASSWORD_RESET_URL", "")
    val scribedCallbackUrl: String = optionalSetting("SCRIBED_CALLBACK_URL", "")
    val scribedIntegrationSecret: String = optionalSetting("SCRIBED_INTEGRATION_SECRET", "")
    val scribedApiToken: String = optionalSetting("SCRIBED_API_TOKEN", "")
    val githubAppId: Long? = optionalSetting("GITHUB_APP_ID", "").toLongOrNull()
    val githubAppInstallationId: Long? = optionalSetting("GITHUB_APP_INSTALLATION_ID", "").toLongOrNull()?.takeIf { it > 0 }
    val githubClientId: String = optionalSetting("GITHUB_CLIENT_ID", "")
    val githubAppSlug: String = optionalSetting("GITHUB_APP_SLUG", "")
    val githubAppInstallUrl: String = optionalSetting("GITHUB_APP_INSTALL_URL", "")
    val githubCallbackUrl: String = optionalSetting("GITHUB_CALLBACK_URL", "")
    val githubAppPrivateKeyPath: String = optionalSetting("GITHUB_APP_PRIVATE_KEY_PATH", "")
    val githubWebhookSecret: String = optionalSetting("GITHUB_WEBHOOK_SECRET", "")
    val deploymentStaleMinutes: Long = optionalSetting("DEPLOYMENT_STALE_MINUTES", "30").toLong()
    val deploymentSecretsKey: String = optionalSetting("DEPLOYMENT_SECRETS_KEY", "")

    init {
        logger.info(
            "Scribed integration configuration: callbackUrlSet={}, integrationSecretSet={}, apiTokenSet={}, workingDirectory={}",
            scribedCallbackUrl.isNotBlank(),
            scribedIntegrationSecret.isNotBlank(),
            scribedApiToken.isNotBlank(),
            System.getProperty("user.dir")
        )
        logger.info("Deployment secret encryption key configured: {}", deploymentSecretsKey.isNotBlank())
    }

    /** Used once on first startup when the users table is empty */
    val adminEmail: String = optionalSetting("ADMIN_EMAIL", "")
    val adminPassword: String = optionalSetting("ADMIN_PASSWORD", "")

    // Docker & Infrastructure
    val dockerSocket: String = optionalSetting("DOCKER_SOCKET", "unix:///var/run/docker.sock")
    val internalNetwork: String = optionalSetting("GATEKEEPER_INTERNAL_NETWORK", "gatekeeper-internal")
    val dockerPullViaCli: Boolean = optionalSetting("DOCKER_PULL_VIA_CLI", "false")
        .trim()
        .lowercase()
        .let { it == "true" || it == "1" || it == "yes" }
    val dockerAllowedRegistries: List<String> = optionalSetting("DOCKER_ALLOWED_REGISTRIES", "")
        .split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
    val dockerAllowedVolumeRoots: List<String> = optionalSetting("DOCKER_ALLOWED_VOLUME_ROOTS", "")
        .split(",").map { it.trim() }.filter { it.isNotBlank() }

    // Nginx
    val nginxSitesAvailablePath: String = optionalSetting("NGINX_SITES_AVAILABLE", "/etc/nginx/sites-available")
    val nginxSitesEnabledPath: String = optionalSetting("NGINX_SITES_ENABLED", "/etc/nginx/sites-enabled")
    val nginxSslCertPath: String = optionalSetting("NGINX_SSL_CERT_PATH", "/etc/letsencrypt/live")

    // Business Logic
    val defaultGracePeriodDays: Int = optionalSetting("DEFAULT_GRACE_PERIOD_DAYS", "3").toInt()
    val failMode: String = optionalSetting("FAIL_MODE", "open")

    val autoBlockerIntervalMinutes: Long =
        optionalSetting("AUTOBLOCKER_INTERVAL_MINUTES", "60").toLong()

    val reconciliationStaleMinutes: Long =
        optionalSetting("RECONCILIATION_STALE_MINUTES", "30").toLong()
    val reconciliationIntervalMinutes: Long =
        optionalSetting("RECONCILIATION_INTERVAL_MINUTES", "15").toLong()

    /** Parsed from CORS_ALLOWED_ORIGINS — comma-separated full origins or bare hostnames */
    val corsAllowedHosts: List<CorsHost> by lazy {
        parseCorsOrigins(
            optionalSetting(
                "CORS_ALLOWED_ORIGINS",
                "https://gatekeeperd.mikesplore.me,http://localhost:5173,http://localhost:8080"
            )
        )
    }

    data class CorsHost(val host: String, val schemes: List<String>)

    private fun parseCorsOrigins(raw: String): List<CorsHost> {
        return raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { origin ->
                if (origin.contains("://")) {
                    val uri = java.net.URI(origin)
                    val port = uri.port
                    val host = when {
                        port <= 0 -> uri.host
                        (uri.scheme == "http" && port == 80) -> uri.host
                        (uri.scheme == "https" && port == 443) -> uri.host
                        else -> "${uri.host}:$port"
                    }
                    CorsHost(host, listOf(uri.scheme))
                } else {
                    CorsHost(origin, listOf("http", "https"))
                }
            }
    }

    fun logConfig() {
        logger.info("AppConfig loaded; configuration values are hidden to protect secrets.")
    }
}
