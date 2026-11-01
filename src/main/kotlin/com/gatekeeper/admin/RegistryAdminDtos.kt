package com.gatekeeper.admin

import kotlinx.serialization.Serializable

@Serializable data class RegistryCredentialRequest(val username: String, val password: String, val displayName: String? = null)
@Serializable data class RegistryMetadataResponse(val registry: String, val username: String, val configured: Boolean)
@Serializable data class RegistryCredentialResponse(
    val registry: String,
    val username: String,
    val configured: Boolean,
    val credentialId: String,
    val version: Int
)

@Serializable
data class ProviderCredentialMetadataResponse(
    val id: String, val provider: String, val type: String, val displayName: String,
    val scope: String, val version: Int, val current: Boolean, val rotatedAt: String?,
    val rotatedBy: String?, val supersededAt: String?, val supersededById: String?,
    val createdAt: String, val createdBy: String?, val updatedAt: String, val updatedBy: String?
)
