package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.Services
import com.gatekeeper.db.tables.AccessBlockReason
import com.gatekeeper.db.tables.DeploymentConfigurations
import com.gatekeeper.db.tables.DeploymentExecutions
import com.gatekeeper.db.tables.DeploymentJobs
import com.gatekeeper.db.tables.Deployments
import com.gatekeeper.db.tables.ProjectSecretSetVersions
import com.gatekeeper.db.tables.ProjectAdjustments
import com.gatekeeper.db.tables.Sites
import com.gatekeeper.plugins.RedisService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.deleteWhere
import java.util.UUID

/** Persistence for service-level customer access policy. Runtime health is deliberately separate. */
object ServiceRepository {
    data class ServiceRecord(
        val id: UUID,
        val projectId: UUID,
        val name: String,
        val accessStatus: String,
        val blockReason: String?,
        val blockReasonCode: AccessBlockReason? = null,
        val blockReasonNote: String? = null
    )

    data class AccessRecord(
        val id: UUID,
        val name: String,
        val accessStatus: String,
        val blockReason: String?,
        val blockReasonCode: AccessBlockReason?,
        val blockReasonNote: String?
    )

    fun listByProjectId(projectId: UUID): List<ServiceRecord> = transaction {
        Services.selectAll().where { Services.projectId eq projectId }
            .orderBy(Services.name).map(::toRecord)
    }

    fun listAll(): List<ServiceRecord> = transaction {
        Services.selectAll().orderBy(Services.projectId to org.jetbrains.exposed.sql.SortOrder.ASC, Services.name to org.jetbrains.exposed.sql.SortOrder.ASC).map(::toRecord)
    }

    fun findByProjectAndId(projectId: UUID, id: UUID): ServiceRecord? = transaction {
        Services.selectAll().where { (Services.projectId eq projectId) and (Services.id eq id) }
            .singleOrNull()?.let(::toRecord)
    }

    /** Returns the project's default service, repairing legacy projects missing the row. */
    fun getOrCreateDefault(projectId: UUID): ServiceRecord = transaction {
        Services.insertIgnore {
            it[Services.projectId] = projectId
            it[Services.name] = "default"
            it[Services.accessStatus] = "active"
            it[Services.blockReason] = null
        }
        Services.selectAll().where {
            (Services.projectId eq projectId) and (Services.name eq "default")
        }.single().let(::toRecord)
    }

    fun create(projectId: UUID, name: String): ServiceRecord = transaction {
        val id = UUID.randomUUID()
        Services.insert {
            it[Services.id] = id
            it[Services.projectId] = projectId
            it[Services.name] = name
            it[Services.accessStatus] = "active"
            it[Services.blockReason] = null
        }
        Services.selectAll().where { Services.id eq id }.single().let(::toRecord)
    }

    fun update(
        projectId: UUID,
        id: UUID,
        name: String? = null,
        accessStatus: String? = null,
        blockReason: String? = null,
        blockReasonCode: AccessBlockReason? = null,
        blockReasonNote: String? = null
    ): ServiceRecord? {
        accessStatus?.let { require(it in setOf("active", "blocked", "manual_block")) { "Unsupported service access status" } }
        val updated = transaction {
            val existing = Services.selectAll().where {
                (Services.projectId eq projectId) and (Services.id eq id)
            }.singleOrNull() ?: return@transaction null
            Services.update({ (Services.projectId eq projectId) and (Services.id eq id) }) {
                name?.let { value -> it[Services.name] = value }
                accessStatus?.let { value -> it[Services.accessStatus] = value }
                if (accessStatus == "active") {
                    it[Services.blockReason] = null
                    it[Services.blockReasonCode] = null
                    it[Services.blockReasonNote] = null
                } else if (accessStatus != null && accessStatus != "active") {
                    val reasonCode = blockReasonCode ?: AccessBlockReason.fromLegacy(blockReason, accessStatus)
                        ?: AccessBlockReason.MANUAL_HOLD
                    val note = blockReasonNote ?: AccessBlockReason.legacyNote(blockReason)
                    it[Services.blockReason] = blockReason ?: reasonCode.value
                    it[Services.blockReasonCode] = reasonCode
                    it[Services.blockReasonNote] = note
                } else {
                    blockReasonCode?.let { value -> it[Services.blockReasonCode] = value }
                    blockReasonNote?.let { value -> it[Services.blockReasonNote] = value }
                    blockReason?.let { value -> it[Services.blockReason] = value }
                }
            }
            Services.selectAll().where { Services.id eq existing[Services.id] }.single().let(::toRecord)
        }
        if (updated != null && (accessStatus != null || name != null)) {
            SiteRepository.findGateSlugsByServiceId(id).forEach(::invalidateGateCache)
        }
        return updated
    }

    /** Only empty non-default services can be removed; referenced runtime history is preserved. */
    fun delete(projectId: UUID, id: UUID): Boolean = transaction {
        val service = Services.selectAll().where {
            (Services.projectId eq projectId) and (Services.id eq id)
        }.singleOrNull() ?: return@transaction false
        if (service[Services.name] == "default") return@transaction false
        val inUse = DeploymentConfigurations.selectAll().where { DeploymentConfigurations.serviceId eq id }.count() > 0L ||
            DeploymentExecutions.selectAll().where { DeploymentExecutions.serviceId eq id }.count() > 0L ||
            DeploymentJobs.selectAll().where { DeploymentJobs.serviceId eq id }.count() > 0L ||
            Deployments.selectAll().where { Deployments.serviceId eq id }.count() > 0L ||
            ProjectSecretSetVersions.selectAll().where { ProjectSecretSetVersions.serviceId eq id }.count() > 0L ||
            ProjectAdjustments.selectAll().where { ProjectAdjustments.serviceId eq id }.count() > 0L ||
            Sites.selectAll().where { Sites.serviceId eq id }.count() > 0L
        if (inUse) return@transaction false
        Services.deleteWhere { (Services.projectId eq projectId) and (Services.id eq id) } > 0
    }

    fun findAccessById(id: UUID): AccessRecord? = transaction {
        Services.selectAll().where { Services.id eq id }.singleOrNull()?.let {
            AccessRecord(it[Services.id], it[Services.name], it[Services.accessStatus], it[Services.blockReason], it[Services.blockReasonCode], it[Services.blockReasonNote])
        }
    }

    private fun toRecord(row: org.jetbrains.exposed.sql.ResultRow) = ServiceRecord(
        row[Services.id], row[Services.projectId], row[Services.name],
        row[Services.accessStatus], row[Services.blockReason], row[Services.blockReasonCode], row[Services.blockReasonNote]
    )

    fun updateAccessStatus(id: UUID, status: String, blockReason: String? = null): Boolean {
        require(status in setOf("active", "blocked", "manual_block")) { "Unsupported service access status" }
        val updated = transaction {
            Services.update({ Services.id eq id }) {
                it[Services.accessStatus] = status
                it[Services.blockReason] = if (status == "active") null else blockReason
                it[Services.blockReasonCode] = if (status == "active") null else AccessBlockReason.fromLegacy(blockReason, status) ?: AccessBlockReason.MANUAL_HOLD
                it[Services.blockReasonNote] = if (status == "active") null else AccessBlockReason.legacyNote(blockReason)
            }
        }
        if (updated > 0) {
            SiteRepository.findGateSlugsByServiceId(id).forEach(::invalidateGateCache)
        }
        return updated > 0
    }

    /** Auto-block only an active service so manual or existing payment blocks are never overwritten. */
    fun autoBlockForPayment(projectId: UUID, id: UUID): Boolean {
        val updated = transaction {
            Services.update({
                (Services.projectId eq projectId) and (Services.id eq id) and (Services.accessStatus eq "active")
            }) {
                it[Services.accessStatus] = "blocked"
                it[Services.blockReason] = "overdue"
                it[Services.blockReasonCode] = AccessBlockReason.PAYMENT
                it[Services.blockReasonNote] = null
            }
        }
        if (updated > 0) SiteRepository.findGateSlugsByServiceId(id).forEach(::invalidateGateCache)
        return updated > 0
    }

    /** Clear only a payment-coded service block after its linked invoice is paid. */
    fun clearAutoBlockForPayment(id: UUID): Boolean {
        val updated = transaction {
            Services.update({
                (Services.id eq id) and (Services.accessStatus eq "blocked") and (Services.blockReasonCode eq AccessBlockReason.PAYMENT)
            }) {
                it[Services.accessStatus] = "active"
                it[Services.blockReason] = null
                it[Services.blockReasonCode] = null
                it[Services.blockReasonNote] = null
            }
        }
        if (updated > 0) SiteRepository.findGateSlugsByServiceId(id).forEach(::invalidateGateCache)
        return updated > 0
    }

    private fun invalidateGateCache(slug: String) {
        try {
            RedisService.delete("project:status:$slug")
        } catch (_: Exception) {
            // Cache invalidation is best effort; the short TTL bounds stale access decisions.
        }
    }
}
