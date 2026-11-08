package com.gatekeeper.feature.service.data.persistence

import com.gatekeeper.db.tables.*
import com.gatekeeper.feature.service.domain.repository.AccessRecord
import com.gatekeeper.feature.service.domain.repository.ServiceRecord
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.plugins.GateCacheInvalidator
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

/** Persistence for service-level customer access policy. Runtime health is deliberately separate. */
class ExposedServiceRepository(
    private val siteRepository: SiteRepository,
    private val gateCacheInvalidator: GateCacheInvalidator = GateCacheInvalidator()
) : ServiceRepository {
    override fun listByProjectId(projectId: UUID): List<ServiceRecord> = transaction {
        Services.selectAll().where { Services.projectId eq projectId }
            .orderBy(Services.name).map(::toRecord)
    }

    override fun findById(id: UUID): ServiceRecord? = transaction {
        Services.selectAll().where { Services.id eq id }.singleOrNull()?.let {
            toRecord(it)
        }
    }


    override fun listAll(): List<ServiceRecord> = transaction {
        Services.selectAll().orderBy(
            Services.projectId to SortOrder.ASC,
            Services.name to SortOrder.ASC
        ).map(::toRecord)
    }

    override fun findByProjectAndId(projectId: UUID, id: UUID): ServiceRecord? = transaction {
        Services.selectAll().where { (Services.projectId eq projectId) and (Services.id eq id) }
            .singleOrNull()?.let(::toRecord)
    }

    override fun updateDomain(projectId: UUID, id: UUID, domain: String): ServiceRecord? = transaction {
        val updated = Services.update({ (Services.projectId eq projectId) and (Services.id eq id) }) {
            it[Services.domain] = domain
        }
        if (updated == 0) return@transaction null
        Services.selectAll().where { (Services.projectId eq projectId) and (Services.id eq id) }
            .singleOrNull()?.let(::toRecord)
    }

    override fun findDefaultDomain(projectId: UUID): String? = transaction {
        Services.selectAll().where {
            (Services.projectId eq projectId) and (Services.isDefault eq true)
        }.singleOrNull()?.get(Services.domain)
    }

    /** Returns the project's default service, repairing legacy projects missing the row. */
    override fun getOrCreateDefault(projectId: UUID): ServiceRecord = transaction {
        Services.insertIgnore {
            it[Services.projectId] = projectId
            it[Services.name] = "default"
            it[Services.serviceType] = "frontend"
            it[Services.isDefault] = true
            it[Services.accessStatus] = "active"
            it[Services.blockReason] = null
        }
        Services.selectAll().where {
            (Services.projectId eq projectId) and (Services.isDefault eq true)
        }.single().let(::toRecord)
    }

    override fun create(projectId: UUID, name: String, serviceType: String): ServiceRecord = transaction {
        val id = UUID.randomUUID()
        Services.insert {
            it[Services.id] = id
            it[Services.projectId] = projectId
            it[Services.name] = name
            it[Services.serviceType] = serviceType
            it[Services.isDefault] = false
            it[Services.accessStatus] = "active"
            it[Services.blockReason] = null
        }
        Services.selectAll().where { Services.id eq id }.single().let(::toRecord)
    }

    override fun update(
        projectId: UUID,
        id: UUID,
        name: String?,
        accessStatus: String?,
        blockReason: String?,
        blockReasonCode: AccessBlockReason?,
        blockReasonNote: String?,
        makeDefault: Boolean,
        serviceType: String?
    ): ServiceRecord? {
        accessStatus?.let {
            require(
                it in setOf(
                    "active",
                    "blocked",
                    "manual_block"
                )
            ) { "Unsupported service access status" }
        }
        val previousGateSlugs = if (makeDefault) siteRepository.findGateSlugsByProjectId(projectId) else emptyList()
        val updated = transaction {
            val existing = Services.selectAll().where {
                (Services.projectId eq projectId) and (Services.id eq id)
            }.singleOrNull() ?: return@transaction null
            if (makeDefault && !existing[Services.isDefault]) {
                Services.update({ Services.projectId eq projectId }) {
                    it[Services.isDefault] = false
                }
            }
            Services.update({ (Services.projectId eq projectId) and (Services.id eq id) }) {
                name?.let { value -> it[Services.name] = value }
                serviceType?.let { value -> it[Services.serviceType] = value }
                if (makeDefault) it[Services.isDefault] = true
                accessStatus?.let { value -> it[Services.accessStatus] = value }
                if (accessStatus == "active") {
                    it[Services.blockReason] = null
                    it[Services.blockReasonCode] = null
                    it[Services.blockReasonNote] = null
                } else if (accessStatus != null) {
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
        if (updated != null && (accessStatus != null || name != null || makeDefault)) {
            val previousGateSlugs = if (makeDefault) siteRepository.findGateSlugsByProjectId(projectId) else emptyList()
            val gateSlugs = if (makeDefault) previousGateSlugs + siteRepository.findGateSlugsByProjectId(projectId)
            else siteRepository.findGateSlugsByServiceId(id)
            gateSlugs.distinct().forEach(::invalidateGateCache)
        }
        return updated
    }

    /** Remove active service state while detaching retained history from the deleted identity. */
    override fun delete(projectId: UUID, id: UUID): Boolean {
        val gateSlugs = siteRepository.findGateSlugsByServiceId(id)
        val deleted = transaction {
            val service = Services.selectAll().where {
                (Services.projectId eq projectId) and (Services.id eq id)
            }.singleOrNull() ?: return@transaction false
            if (service[Services.isDefault]) return@transaction false
            // Preserve operational and billing history, but detach its nullable service FK
            // before removing the service record. Site and service-domain rows are live state.
            DeploymentConfigurations.update({ DeploymentConfigurations.serviceId eq id }) { it[serviceId] = null }
            DeploymentExecutions.update({ DeploymentExecutions.serviceId eq id }) { it[serviceId] = null }
            DeploymentJobs.update({ DeploymentJobs.serviceId eq id }) { it[serviceId] = null }
            Deployments.update({ Deployments.serviceId eq id }) { it[serviceId] = null }
            ProjectSecretSetVersions.update({ ProjectSecretSetVersions.serviceId eq id }) { it[serviceId] = null }
            ProjectAdjustments.update({ ProjectAdjustments.serviceId eq id }) { it[serviceId] = null }
            Sites.deleteWhere { Sites.serviceId eq id }
            service[Services.domain]?.let { domain ->
                Sites.deleteWhere { (Sites.projectId eq projectId) and (Sites.domain eq domain) }
            }
            ServiceDomains.selectAll().where { ServiceDomains.serviceId eq id }.forEach { binding ->
                Sites.deleteWhere {
                    (Sites.projectId eq projectId) and (Sites.domain eq binding[ServiceDomains.domain])
                }
            }
            Services.deleteWhere { (Services.projectId eq projectId) and (Services.id eq id) } > 0
        }
        if (deleted) gateSlugs.forEach(::invalidateGateCache)
        return deleted
    }

    override fun findAccessById(id: UUID): AccessRecord? = transaction {
        Services.selectAll().where { Services.id eq id }.singleOrNull()?.let {
            AccessRecord(
                it[Services.id],
                it[Services.name],
                it[Services.serviceType],
                it[Services.accessStatus],
                it[Services.blockReason],
                it[Services.blockReasonCode],
                it[Services.blockReasonNote]
            )
        }
    }

    private fun toRecord(row: ResultRow) = ServiceRecord(
        row[Services.id],
        row[Services.projectId],
        row[Services.name],
        row[Services.serviceType],
        row[Services.domain],
        row[Services.isDefault],
        row[Services.accessStatus],
        row[Services.blockReason],
        row[Services.blockReasonCode],
        row[Services.blockReasonNote]
    )

    override fun updateAccessStatus(id: UUID, status: String, blockReason: String?): Boolean {
        require(status in setOf("active", "blocked", "manual_block")) { "Unsupported service access status" }
        val updated = transaction {
            Services.update({ Services.id eq id }) {
                it[Services.accessStatus] = status
                it[Services.blockReason] = if (status == "active") null else blockReason
                it[Services.blockReasonCode] =
                    if (status == "active") null else AccessBlockReason.fromLegacy(blockReason, status)
                        ?: AccessBlockReason.MANUAL_HOLD
                it[Services.blockReasonNote] =
                    if (status == "active") null else AccessBlockReason.legacyNote(blockReason)
            }
        }
        if (updated > 0) {
            siteRepository.findGateSlugsByServiceId(id).forEach(::invalidateGateCache)
        }
        return updated > 0
    }

    /** Auto-block only an active service so manual or existing payment blocks are never overwritten. */
    override fun autoBlockForPayment(projectId: UUID, id: UUID): Boolean {
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
        if (updated > 0) siteRepository.findGateSlugsByServiceId(id).forEach(::invalidateGateCache)
        return updated > 0
    }

    /** Clear only a payment-coded service block after its linked invoice is paid. */
    override fun clearAutoBlockForPayment(id: UUID): Boolean {
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
        if (updated > 0) siteRepository.findGateSlugsByServiceId(id).forEach(::invalidateGateCache)
        return updated > 0
    }

    private fun invalidateGateCache(slug: String) {
        gateCacheInvalidator.invalidate(listOf(slug))
    }
}


