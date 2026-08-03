package com.gatekeeper.nginx

import kotlinx.serialization.Serializable

@Serializable
data class NginxEnableRequest(
    val port: Int? = null,
    val upstreamScheme: String? = null,
    val certificateDomain: String? = null,
    val sslCertificatePath: String? = null,
    val sslCertificateKeyPath: String? = null,
    val requireSsl: Boolean? = null
)

@Serializable
data class NginxStatusResponse(
    val enabled: Boolean,
    val configPath: String? = null,
    val enabledPath: String? = null,
    val port: Int? = null,
    val sslEnabled: Boolean,
    val certificateDomain: String? = null,
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

@Serializable
data class InstalledCertificateInfo(
    val certificateDomain: String,
    val certificatePath: String,
    val privateKeyPath: String
)

@Serializable
data class CertificateListResponse(
    val certificates: List<InstalledCertificateInfo>
)
