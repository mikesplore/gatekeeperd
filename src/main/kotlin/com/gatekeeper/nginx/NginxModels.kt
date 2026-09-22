package com.gatekeeper.nginx

import kotlinx.serialization.Serializable

@Serializable
data class NginxEnableRequest(
    val port: Int? = null,
    val domain: String? = null,
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
    ,val managed: Boolean = false,
    val drifted: Boolean = false,
    val actualSha256: String? = null,
    val managedSha256: String? = null
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

@Serializable data class NginxBlockUpdateRequest(val blockIndex: Int, val content: String)
@Serializable data class NginxBlockUpdateResponse(val success: Boolean, val message: String, val config: String, val blockIndex: Int, val validation: NginxTestResult, val reloaded: Boolean = false)
@Serializable data class NginxBackup(val name: String, val createdAt: String, val sizeBytes: Long)
@Serializable data class NginxRollbackResponse(val success: Boolean, val message: String, val validation: NginxTestResult, val reloaded: Boolean = false)

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
