package com.gatekeeper.nginx

import kotlinx.serialization.Serializable

@Serializable
data class NginxEnableRequest(
    val port: Int,
    val sslCertificatePath: String? = null,
    val sslCertificateKeyPath: String? = null
)

@Serializable
data class NginxStatusResponse(
    val enabled: Boolean,
    val configPath: String? = null,
    val enabledPath: String? = null,
    val port: Int? = null,
    val sslEnabled: Boolean,
    val domain: String? = null
)

@Serializable
data class CertificateInstallRequest(
    val domain: String,
    val email: String
)

@Serializable
data class CertificateResponse(
    val domain: String,
    val installed: Boolean,
    val certificatePath: String? = null,
    val privateKeyPath: String? = null
)