package com.gatekeeper.config

import io.github.cdimascio.dotenv.dotenv
import com.gatekeeper.config.AppEnvironment

/**
 * Immutable snapshot of process configuration.
 *
 * Environment access is deliberately confined to [fromEnv]. The optional .env
 * file remains a local-development fallback, matching the legacy precedence.
 */
class AppSettings private constructor(
    private val values: Map<String, String>
) {
    data class DbSettings(
        val url: String,
        val user: String,
        val password: String,
        val migrationBaselineVersion: String
    )

    data class RedisSettings(val host: String, val port: Int)

    data class DockerSettings(
        val socket: String,
        val internalNetwork: String,
        val pullViaCli: Boolean,
        val allowedRegistries: List<String>,
        val allowedVolumeRoots: List<String>
    )

    data class NginxSettings(
        val deploymentUpstreamsEnabled: Boolean,
        val hostOperationsEnabled: Boolean,
        val certbotEnabled: Boolean,
        val nginxBinary: String,
        val developmentConfigPath: String?,
        val sitesAvailablePath: String,
        val sitesEnabledPath: String,
        val upstreamIncludeDirectory: String,
        val sslCertPath: String,
        val selfDomains: List<String>,
        val environment: AppEnvironment,
        val customCertDirectory: String?
    )

    data class AuthSettings(val jwtSecret: String, val issuer: String, val audience: String)

    data class GitHubSettings(
        val appId: Long?,
        val installationId: Long?,
        val clientId: String,
        val appSlug: String,
        val installUrl: String,
        val callbackUrl: String,
        val privateKeyPath: String,
        val webhookSecret: String
    )

    data class IntegrationSettings(
        val publicBaseUrl: String,
        val frontendBaseUrl: String,
        val supportContactEmail: String,
        val resendApiKey: String,
        val resendFromEmail: String,
        val passwordResetUrl: String,
        val scribedBaseUrl: String,
        val scribedApiClientToken: String,
        val scribedApiToken: String
    )

    data class TelemetrySettings(val otlpEndpoint: String?)

    data class OperationsSettings(val nginxConfigLogEnabled: Boolean)

    data class ApplicationSettings(
        val adminEmail: String,
        val adminPassword: String,
        val deploymentSecretsKey: String,
        val failMode: FailMode,
        val corsAllowedHosts: List<CorsHost>,
        val defaultGracePeriodDays: Int,
        val deploymentStaleMinutes: Long,
        val deploymentHostPortRange: IntRange,
        val deploymentDrainDelaySeconds: Long
    )

    enum class FailMode { OPEN, CLOSED;
        companion object {
            fun parse(value: String): FailMode = when (value.trim().lowercase()) {
                "open" -> OPEN
                "closed" -> CLOSED
                else -> error("FAIL_MODE must be either 'open' or 'closed'")
            }
        }
    }

    data class CorsHost(val host: String, val schemes: List<String>)

    data class PaymentSettings(
        val paystackSecretKey: String,
        val paystackPublicKey: String,
        val mpesaConsumerKey: String,
        val mpesaConsumerSecret: String,
        val mpesaShortCode: String,
        val mpesaPasskey: String,
        val mpesaCallbackUrl: String,
        val mpesaCallbackToken: String,
        val mpesaEnvironment: String,
        val mpesaRateLimitWindowSeconds: Int,
        val mpesaRateLimitIpMax: Int,
        val mpesaRateLimitServiceMax: Int,
        val mpesaRateLimitPhoneMax: Int,
        val mpesaInitiationCooldownSeconds: Int
    )

    data class SchedulerSettings(
        val defaultGracePeriodDays: Int,
        val autoBlockerIntervalMinutes: Long,
        val reconciliationStaleMinutes: Long,
        val reconciliationIntervalMinutes: Long,
        val deploymentStaleMinutes: Long,
        val deploymentHostPortRange: IntRange,
        val deploymentDrainDelaySeconds: Long
    )

    val db: DbSettings by lazy {
        DbSettings(
            required("DB_URL"),
            required("DB_USER"),
            required("DB_PASSWORD"),
            optional("DB_MIGRATION_BASELINE_VERSION", "0")
        )
    }

    val environment: AppEnvironment by lazy {
        // Check the raw source or property value instead of the lazy property 'environment'
        val rawEnv = optional("GATEKEEPERD_ENVIRONMENT", "DEV")
        AppEnvironment.fromValue(rawEnv)
    }
    val redis: RedisSettings by lazy { RedisSettings(required("REDIS_HOST"), optional("REDIS_PORT", "6379").toInt()) }
    val docker: DockerSettings by lazy {
        DockerSettings(
            optional("DOCKER_SOCKET", "unix:///var/run/docker.sock"),
            optional("GATEKEEPER_INTERNAL_NETWORK", "gk-internal"),
            boolean("DOCKER_PULL_VIA_CLI", false),
            csv("DOCKER_ALLOWED_REGISTRIES", lowercase = true),
            csv("DOCKER_ALLOWED_VOLUME_ROOTS")
        )
    }
    val nginx: NginxSettings by lazy {
        val hostOperationsEnabled = boolean("GATEKEEPERD_NGINX_HOST_SUDO", false)
        val isolatedDevelopmentNginx = environment == AppEnvironment.DEV && !hostOperationsEnabled
        val localSitesAvailable = "nginx/sites-available"
        val localSitesEnabled = "nginx/sites-enabled"
        val localUpstreamDirectory = "nginx/conf.d/gatekeeperd-upstreams"
        val localCertificateDirectory = "nginx/custom-certs"
        NginxSettings(
            deploymentUpstreamsEnabled = boolean("NGINX_DEPLOYMENT_UPSTREAMS_ENABLED", environment != AppEnvironment.DEV),
            hostOperationsEnabled = hostOperationsEnabled,
            certbotEnabled = boolean("GATEKEEPERD_NGINX_CERTBOT_ENABLED", false),
            nginxBinary = optional("NGINX_BINARY", "/usr/sbin/nginx"),
            developmentConfigPath = if (environment == AppEnvironment.DEV) optional("NGINX_CONFIG_PATH", "nginx/nginx.conf") else null,
            sitesAvailablePath = if (isolatedDevelopmentNginx) localSitesAvailable else sitesAvailablePath(),
            sitesEnabledPath = if (isolatedDevelopmentNginx) localSitesEnabled else sitesEnabledPath(),
            upstreamIncludeDirectory = if (isolatedDevelopmentNginx) localUpstreamDirectory else optional(
                "NGINX_UPSTREAM_INCLUDE_DIR",
                if (environment == AppEnvironment.DEV) "nginx/conf.d/gatekeeperd-upstreams" else "/etc/nginx/conf.d/gatekeeperd-upstreams"
            ),
            sslCertPath = if (isolatedDevelopmentNginx) localCertificateDirectory else sslCertPath(),
            customCertDirectory = if (isolatedDevelopmentNginx) localCertificateDirectory else customCertDirectory(),
            selfDomains = required("GATEKEEPERD_SELF_DOMAIN").split(',').map { it.trim().trimEnd('.').lowercase() },
            environment = environment
        )
    }
    val auth: AuthSettings by lazy { AuthSettings(required("JWT_SECRET"), optional("JWT_ISSUER", "gatekeeperd"), optional("JWT_AUDIENCE", "gatekeeperd-admin")) }
    val github: GitHubSettings by lazy {
        GitHubSettings(
            optional("GITHUB_APP_ID", "").toLongOrNull(),
            optional("GITHUB_APP_INSTALLATION_ID", "").toLongOrNull()?.takeIf { it > 0 },
            optional("GITHUB_CLIENT_ID", ""), optional("GITHUB_APP_SLUG", ""),
            optional("GITHUB_APP_INSTALL_URL", ""), optional("GITHUB_CALLBACK_URL", ""),
            optional("GITHUB_APP_PRIVATE_KEY_PATH", ""), optional("GITHUB_WEBHOOK_SECRET", "")
        )
    }
    val integrations: IntegrationSettings by lazy {
        IntegrationSettings(
            optional("GATEKEEPER_PUBLIC_URL", ""), optional("GATEKEEPER_FRONTEND_URL", ""),
            optional("SUPPORT_CONTACT_EMAIL", "support@gatekeeper.local"), optional("RESEND_API_KEY", ""),
            optional("RESEND_FROM_EMAIL", ""), optional("PASSWORD_RESET_URL", ""),
            optional("SCRIBED_BASE_URL", ""), optional("SCRIBED_API_CLIENT_TOKEN", ""),
            optional("SCRIBED_API_TOKEN", "")
        )
    }
    val telemetry: TelemetrySettings by lazy { TelemetrySettings(get("OTEL_EXPORTER_OTLP_ENDPOINT")) }
    val operations: OperationsSettings by lazy { OperationsSettings(boolean("NGINX_CONFIG_LOG_ENABLED", true)) }
    val application: ApplicationSettings by lazy {
        ApplicationSettings(
            optional("ADMIN_EMAIL", ""), optional("ADMIN_PASSWORD", ""),
            optional("DEPLOYMENT_SECRETS_KEY", ""), FailMode.parse(optional("FAIL_MODE", "closed")), corsHosts(),
            optional("DEFAULT_GRACE_PERIOD_DAYS", "3").toInt(),
            optional("DEPLOYMENT_STALE_MINUTES", "30").toLong(),
            parsePortRange(optional("DEPLOYMENT_HOST_PORT_RANGE", "32000-40000")),
            optional("DEPLOYMENT_DRAIN_DELAY_SECONDS", "5").toLong().also {
                require(it in 0..300) { "DEPLOYMENT_DRAIN_DELAY_SECONDS must be between 0 and 300" }
            }
        )
    }
    val payment: PaymentSettings by lazy {
        PaymentSettings(
            optional("PAYSTACK_SECRET_KEY", ""), optional("PAYSTACK_PUBLIC_KEY", ""),
            optional("MPESA_CONSUMER_KEY", ""), optional("MPESA_CONSUMER_SECRET", ""),
            optional("MPESA_SHORT_CODE", optional("MPESA_SHORTCODE", "")), optional("MPESA_PASSKEY", ""),
            optional("MPESA_CALLBACK_URL", ""), optional("MPESA_CALLBACK_TOKEN", ""),
            optional("MPESA_ENVIRONMENT", "sandbox"),
            optional("MPESA_RATE_LIMIT_WINDOW_SECONDS", "60").toInt().also { require(it in 1..3600) { "MPESA_RATE_LIMIT_WINDOW_SECONDS must be between 1 and 3600" } },
            optional("MPESA_RATE_LIMIT_IP_MAX", "120").toInt().also { require(it > 0) { "MPESA_RATE_LIMIT_IP_MAX must be positive" } },
            optional("MPESA_RATE_LIMIT_SERVICE_MAX", "30").toInt().also { require(it > 0) { "MPESA_RATE_LIMIT_SERVICE_MAX must be positive" } },
            optional("MPESA_RATE_LIMIT_PHONE_MAX", "3").toInt().also { require(it > 0) { "MPESA_RATE_LIMIT_PHONE_MAX must be positive" } },
            optional("MPESA_INITIATION_COOLDOWN_SECONDS", "60").toInt().also { require(it in 1..3600) { "MPESA_INITIATION_COOLDOWN_SECONDS must be between 1 and 3600" } }
        )
    }
    val scheduler: SchedulerSettings by lazy {
        SchedulerSettings(
            optional("DEFAULT_GRACE_PERIOD_DAYS", "3").toInt(),
            optional("AUTOBLOCKER_INTERVAL_MINUTES", "60").toLong(),
            optional("RECONCILIATION_STALE_MINUTES", "30").toLong(),
            optional("RECONCILIATION_INTERVAL_MINUTES", "15").toLong(),
            optional("DEPLOYMENT_STALE_MINUTES", "30").toLong(),
            parsePortRange(optional("DEPLOYMENT_HOST_PORT_RANGE", "32000-40000")),
            optional("DEPLOYMENT_DRAIN_DELAY_SECONDS", "5").toLong()
        )
    }

    operator fun get(key: String): String? = values[key]?.takeIf { it.isNotBlank() }

    private fun required(key: String): String = get(key) ?: error("Missing required configuration '$key'")
    private fun optional(key: String, default: String): String = get(key) ?: default
    private fun boolean(key: String, default: Boolean): Boolean = optional(key, default.toString()).lowercase() in setOf("true", "1", "yes")
    private fun csv(key: String, lowercase: Boolean = false): List<String> = optional(key, "").split(',').map { it.trim() }.filter { it.isNotBlank() }.map { if (lowercase) it.lowercase() else it }

    private fun parsePortRange(raw: String): IntRange {
        val parts = raw.split('-', limit = 2)
        require(parts.size == 2) { "DEPLOYMENT_HOST_PORT_RANGE must use START-END format" }
        val start = parts[0].toIntOrNull() ?: error("DEPLOYMENT_HOST_PORT_RANGE start must be an integer")
        val end = parts[1].toIntOrNull() ?: error("DEPLOYMENT_HOST_PORT_RANGE end must be an integer")
        require(start in 1024..65535 && end in 1024..65535 && start <= end) { "DEPLOYMENT_HOST_PORT_RANGE must be an ordered range between 1024 and 65535" }
        return start..end
    }

    private fun sitesAvailablePath(): String = optional("NGINX_SITES_AVAILABLE", if (environment == AppEnvironment.DEV) "nginx/sites-available" else "/etc/nginx/sites-available")
    private fun sitesEnabledPath(): String = optional("NGINX_SITES_ENABLED", if (environment == AppEnvironment.DEV) "nginx/sites-enabled" else "/etc/nginx/sites-enabled")
    private fun sslCertPath(): String = optional("NGINX_SSL_CERT_PATH", if (environment == AppEnvironment.DEV) customCertDirectory() else "/etc/letsencrypt/live")
    private fun customCertDirectory(): String = optional("NGINX_CUSTOM_CERT_DIR", if (environment == AppEnvironment.DEV) "nginx/custom-certs" else "/etc/letsencrypt")
    private fun corsHosts(): List<CorsHost> = optional(
        "CORS_ALLOWED_ORIGINS",
        "https://gatekeeperd.mikesplore.me,http://localhost:5173,http://localhost:8080"
    ).split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { origin ->
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
        } else CorsHost(origin, listOf("http", "https"))
    }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv(), loadDotenv: Boolean = true): AppSettings {
            val local = if (loadDotenv) dotenv {
                filename = ".env"
                ignoreIfMissing = true
            } else null
            val merged = buildMap {
                local?.entries()?.forEach { entry -> put(entry.key, entry.value) }
                putAll(env)
            }
            return AppSettings(merged.toMap())
        }
    }
}
