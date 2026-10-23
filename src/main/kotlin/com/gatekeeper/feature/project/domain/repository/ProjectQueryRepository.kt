package com.gatekeeper.feature.project.domain.repository

import com.gatekeeper.db.tables.AccessBlockReason
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import java.util.UUID

/** Repository ports used by public gate and customer application services. */
interface ProjectQueryRepository {
    fun findBySlug(slug: String): ProjectRecord?
    fun findGateTargetBySlug(slug: String): GateTarget?
    fun findGateTargetByDomain(domain: String): GateTarget?
}
data class GateTarget(
    val project: ProjectRecord,
    val serviceId: UUID,
    val siteDomain: String,
    val siteSlug: String,
    val serviceName: String,
    val serviceAccessStatus: String,
    val serviceBlockReason: String?,
    val serviceBlockReasonCode: AccessBlockReason?,
    val serviceBlockReasonNote: String?
)