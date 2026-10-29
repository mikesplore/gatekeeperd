package com.gatekeeper.feature.nginx.domain.port

import com.gatekeeper.config.AppEnvironment

/**
 * Resolves the privilege and host-operation policy from the global
 * [AppEnvironment]. Encapsulates the DEV/PROD distinction so callers never
 * branch on environment themselves.
 *
 * Design decision:
 *   - DEV disables *host privilege* operations by default. Local-directory paths
 *     are *not* privileged, so they always apply in DEV. A local-binary override
 *     is opt-in via [nginxHostSudoEnabled].
 *   - PROD enables restricted sudo -n host operations and certbot install/renew/remove.
 */
class NginxEnvironmentPolicy(
    val environment: AppEnvironment,
    val nginxHostSudoEnabled: Boolean,
    val nginxCertbotEnabled: Boolean,
    val customCertDirectory: String?
) {
    val isDev: Boolean = environment == AppEnvironment.DEV

    // Path fields are *not* privileged; they apply in both environments.
    val isHostMutationAllowed: Boolean = if (isDev) nginxHostSudoEnabled else true

    // Certbot (install/renew/remove) is a host-privilege operation.
    val certbotEnabled: Boolean = if (isDev) nginxCertbotEnabled else true

    // Custom certificate directory is a DEV convenience only.
    val customCertDirectoryAllowed: Boolean = isDev && customCertDirectory != null

    fun nginxTestCommand(): List<String> = if (isHostMutationAllowed) {
        listOf("/usr/sbin/nginx", "-t")
    } else {
        listOf("true")
    }

    fun nginxReloadCommand(): List<String> = if (isHostMutationAllowed) {
        listOf("/bin/systemctl", "reload", "nginx")
    } else {
        listOf("true")
    }

    fun nginxDumpCommand(): List<String> = if (isHostMutationAllowed) {
        listOf("/usr/sbin/nginx", "-T")
    } else {
        listOf("true")
    }
}
