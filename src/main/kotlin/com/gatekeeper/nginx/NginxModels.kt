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
    val domain: String? = null,
    val certificateExpiresAt: String? = null,
    val certificateDaysRemaining: Long? = null
)

@Serializable
data class NginxConfigInspection(
    val slug: String,
    val configPath: String,
    val enabledPath: String,
    val available: Boolean,
    val enabled: Boolean,
    val isSymlink: Boolean,
    val content: String? = null,
    val blocks: List<NginxConfigBlock> = emptyList(),
    val modifiedAt: String? = null,
    val sizeBytes: Long? = null
)

@Serializable
data class NginxConfigBlock(
    val type: String,
    val header: String,
    val content: String
)

@Serializable
data class NginxTestResult(
    val valid: Boolean,
    val exitCode: Int,
    val output: String,
    val checkedAt: String
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
