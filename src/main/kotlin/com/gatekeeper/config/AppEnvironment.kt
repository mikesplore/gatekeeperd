package com.gatekeeper.config

/**
 * Global process environment. Controls privilege and host-operation policy
 * (not just paths) for nginx/certbot/Docker host operations.
 *
 * DEV: local/none privileged host ops, no certbot install/renew/remove by default.
 * PROD: /etc/nginx + /etc/letsencrypt, restricted sudo -n, gatekeeperd-certbot helper.
 */
enum class AppEnvironment {
    DEV,
    PROD;

    companion object {
        fun fromValue(raw: String?): AppEnvironment = when (raw?.trim()?.uppercase()) {
            "DEV", "DEVELOPMENT" -> DEV
            "PROD", "PRODUCTION" -> PROD
            else -> DEV
        }
    }
}
