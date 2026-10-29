package com.gatekeeper.feature.service.domain.repository

import com.gatekeeper.db.tables.AccessBlockReason
import java.util.UUID

/** Persistence port for service identity and service-level customer access policy. */
interface ServiceRepository {
    fun findById(id: UUID): ServiceRecord?
    fun listByProjectId(projectId: UUID): List<ServiceRecord>
    fun listAll(): List<ServiceRecord>
    fun findByProjectAndId(projectId: UUID, id: UUID): ServiceRecord?
    fun updateDomain(projectId: UUID, id: UUID, domain: String): ServiceRecord?
    fun findDefaultDomain(projectId: UUID): String?
    fun getOrCreateDefault(projectId: UUID): ServiceRecord
    fun create(projectId: UUID, name: String, serviceType: String = "frontend"): ServiceRecord
    fun update(
        projectId: UUID,
        id: UUID,
        name: String? = null,
        accessStatus: String? = null,
        blockReason: String? = null,
        blockReasonCode: AccessBlockReason? = null,
        blockReasonNote: String? = null,
        makeDefault: Boolean = false,
        serviceType: String? = null
    ): ServiceRecord?
    fun delete(projectId: UUID, id: UUID): Boolean
    fun findAccessById(id: UUID): AccessRecord?
    fun updateAccessStatus(id: UUID, status: String, blockReason: String? = null): Boolean
    fun autoBlockForPayment(projectId: UUID, id: UUID): Boolean
    fun clearAutoBlockForPayment(id: UUID): Boolean
}
