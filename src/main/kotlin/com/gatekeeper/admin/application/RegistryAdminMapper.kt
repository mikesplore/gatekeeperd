package com.gatekeeper.admin.application


import com.gatekeeper.admin.model.*
import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialMetadata

fun ProviderCredentialMetadata.toMetadataResponse() = ProviderCredentialMetadataResponse(
    id.toString(), provider, credentialType, displayName, scope, version, current,
    rotatedAt?.let { com.gatekeeper.api.ApiTimestamp.format(it) }, rotatedBy, supersededAt?.let { com.gatekeeper.api.ApiTimestamp.format(it) }, supersededById?.toString(),
    com.gatekeeper.api.ApiTimestamp.format(createdAt), createdBy, com.gatekeeper.api.ApiTimestamp.format(updatedAt), updatedBy
)
