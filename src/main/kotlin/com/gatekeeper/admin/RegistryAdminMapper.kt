package com.gatekeeper.admin

import com.gatekeeper.feature.integrations.domain.repository.ProviderCredentialMetadata

fun ProviderCredentialMetadata.toMetadataResponse() = ProviderCredentialMetadataResponse(
    id.toString(), provider, credentialType, displayName, scope, version, current,
    rotatedAt?.toString(), rotatedBy, supersededAt?.toString(), supersededById?.toString(),
    createdAt.toString(), createdBy, updatedAt.toString(), updatedBy
)
