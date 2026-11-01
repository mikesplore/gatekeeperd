package com.gatekeeper.feature.service.domain.repository

import com.gatekeeper.db.tables.AccessBlockReason
import java.util.UUID

data class ServiceRecord(
    val id: UUID,
    val projectId: UUID,
    val name: String,
    val serviceType: String,
    val domain: String?,
    val isDefault: Boolean,
    val accessStatus: String,
    val blockReason: String?,
    val blockReasonCode: AccessBlockReason? = null,
    val blockReasonNote: String? = null
)

data class AccessRecord(
    val id: UUID,
    val name: String,
    val serviceType: String,
    val accessStatus: String,
    val blockReason: String?,
    val blockReasonCode: AccessBlockReason?,
    val blockReasonNote: String?
)
