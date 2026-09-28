package com.gatekeeper.nginx

import kotlinx.serialization.Serializable

@Serializable
data class NginxEnableResponse(val success: Boolean, val message: String, val config: String? = null, val appPort: Int? = null, val sslEnabled: Boolean? = null, val certificateDomain: String? = null)

@Serializable
data class NginxDisableResponse(val success: Boolean, val message: String)

@Serializable
data class NginxWizardContextResponse(
    val slug: String,
    val domain: String,
    val nginxEnabled: Boolean,
    val resolvedUpstreamHost: String? = null,
    val configuredPort: Int? = null,
    val runtimeHealth: String? = null,
    val installedCertificates: List<String> = emptyList(),
    val resolvedCertificateDomain: String? = null
)
