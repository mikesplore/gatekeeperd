package com.gatekeeper.config

import io.github.cdimascio.dotenv.Dotenv
import io.github.cdimascio.dotenv.dotenv
import org.slf4j.LoggerFactory

object AppConfig {
    private val logger = LoggerFactory.getLogger(AppConfig::class.java)

    private val dotenv = dotenv()

    // Database
    val dbUrl: String = dotenv["DB_URL"] ?: "jdbc:postgresql://localhost:5432/gatekeeper"
    val dbUser: String = dotenv["DB_USER"] ?: "gatekeeper"
    val dbPassword: String = dotenv["DB_PASSWORD"] ?: "changeme"

    // Redis
    val redisHost: String = dotenv["REDIS_HOST"] ?: "localhost"
    val redisPort: Int = (dotenv["REDIS_PORT"] ?: "6379").toInt()

    // JWT Auth
    val jwtSecret: String = dotenv["JWT_SECRET"] ?: "changeme-use-a-long-random-string"
    val jwtIssuer: String = dotenv["JWT_ISSUER"] ?: "gatekeeperd"
    val jwtAudience: String = dotenv["JWT_AUDIENCE"] ?: "gatekeeperd-admin"

    // Paystack
    val paystackSecretKey: String = dotenv["PAYSTACK_SECRET_KEY"] ?: "sk_live_xxx"
    val paystackPublicKey: String = dotenv["PAYSTACK_PUBLIC_KEY"] ?: "pk_live_xxx"

    // Docker & Infrastructure
    val dockerSocket: String = dotenv["DOCKER_SOCKET"] ?: "unix:///var/run/docker.sock"
    val internalNetwork: String = dotenv["GATEKEEPER_INTERNAL_NETWORK"] ?: "gatekeeper-internal"

    // Business Logic
    val defaultGracePeriodDays: Int = (dotenv["DEFAULT_GRACE_PERIOD_DAYS"] ?: "3").toInt()
    val failMode: String = dotenv["FAIL_MODE"] ?: "open"

    /** Print all loaded config keys (redacted values) for debugging */
    fun logConfig() {
        val entries = linkedMapOf<String, String>()
        entries["DB_URL"] = dbUrl
        entries["DB_USER"] = dbUser
        entries["REDIS_HOST"] = redisHost
        entries["REDIS_PORT"] = redisPort.toString()
        entries["JWT_SECRET"] = "***"
        entries["JWT_ISSUER"] = jwtIssuer
        entries["JWT_AUDIENCE"] = jwtAudience
        entries["DOCKER_SOCKET"] = dockerSocket
        entries["GATEKEEPER_INTERNAL_NETWORK"] = internalNetwork
        entries["FAIL_MODE"] = failMode
        entries["DEFAULT_GRACE_PERIOD_DAYS"] = defaultGracePeriodDays.toString()
        logger.info("AppConfig loaded (source: .env / system env):")
        entries.forEach { (key, value) ->
            logger.info("  $key = $value")
        }
    }
}