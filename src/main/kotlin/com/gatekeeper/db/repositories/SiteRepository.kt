package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.Projects
import com.gatekeeper.db.tables.Sites
import com.gatekeeper.db.tables.Services
import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.db.tables.ReconciliationStatus
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.*
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import com.gatekeeper.nginx.DEFAULT_GATEKEEPER_BYPASS_PATHS

object SiteRepository {
    fun saveSetupDraft(
        projectId: UUID,
        domain: String,
        tlsMode: TlsMode,
        gateEnabled: Boolean,
        certMode: CertMode = CertMode.AUTO_RESOLVE,
        serviceId: UUID? = null
    ): SiteRecord = transaction {
        val ownerServiceId = serviceId ?: defaultServiceId(projectId)
        check(Services.selectAll().where { (Services.id eq ownerServiceId) and (Services.projectId eq projectId) }.count() == 1L) {
            "Service does not belong to project"
        }
        val current = Sites.selectAll().where { Sites.serviceId eq ownerServiceId }.singleOrNull()
        if (current == null) {
            Sites.insert {
                it[Sites.id] = UUID.randomUUID()
                it[Sites.projectId] = projectId
                it[Sites.serviceId] = ownerServiceId
                it[Sites.domain] = domain
                it[Sites.upstreamHost] = "127.0.0.1"
                it[Sites.upstreamMode] = UpstreamMode.DOCKER_DISCOVERY
                it[Sites.upstreamExplicitPort] = null
                it[Sites.tlsMode] = tlsMode
                it[Sites.certMode] = certMode
                it[Sites.certExplicitPath] = null
                it[Sites.gateEnabled] = gateEnabled
                it[Sites.reconciliationStatus] = ReconciliationStatus.DISABLED
            }
        } else {
            val changed = current[Sites.domain] != domain || current[Sites.tlsMode] != tlsMode ||
                current[Sites.certMode] != certMode || current[Sites.gateEnabled] != gateEnabled
            if (changed) {
                Sites.update({ Sites.id eq current[Sites.id] }) {
                    it[Sites.domain] = domain
                    it[Sites.tlsMode] = tlsMode
                    it[Sites.certMode] = certMode
                    it[Sites.gateEnabled] = gateEnabled
                    it[Sites.configVersion] = current[Sites.configVersion] + 1
                    it[Sites.reconciliationStatus] = ReconciliationStatus.DRIFTED
                    it[Sites.updatedAt] = LocalDateTime.now()
                }
            }
        }
        Sites.selectAll().where { Sites.serviceId eq ownerServiceId }.single().toRecord()
    }

    private fun defaultServiceId(projectId: UUID): UUID = Services.selectAll().where {
        (Services.projectId eq projectId) and (Services.name eq "default")
    }.singleOrNull()?.get(Services.id) ?: error("Default service not found for project $projectId")

    fun existsForProject(projectId: UUID): Boolean = transaction {
        Sites.selectAll().where { Sites.projectId eq projectId }.count() > 0
    }

    fun existsForService(serviceId: UUID): Boolean = transaction {
        Sites.selectAll().where { Sites.serviceId eq serviceId }.count() > 0
    }

    fun existsForDomain(projectId: UUID, domain: String): Boolean = transaction {
        Sites.selectAll().where { (Sites.projectId eq projectId) and (Sites.domain eq domain) }.count() > 0
    }

    data class SiteRecord(
        val id: UUID, val projectId: UUID, val domain: String, val upstreamHost: String,
        val upstreamMode: UpstreamMode, val upstreamExplicitPort: Int?,
        val tlsMode: TlsMode, val certMode: CertMode, val certExplicitPath: String?, val gateEnabled: Boolean,
        val configVersion: Int, val createdAt: LocalDateTime, val updatedAt: LocalDateTime,
        val bypassPaths: List<String> = DEFAULT_GATEKEEPER_BYPASS_PATHS,
        val reconciliationStatus: ReconciliationStatus, val lastNginxError: String?, val lastDockerError: String?, val lastReconciledAt: LocalDateTime?, val projectSlug: String? = null,
        val serviceId: UUID? = null
    )

    fun findByProjectId(projectId: UUID): SiteRecord? = transaction {
        val serviceId = defaultServiceId(projectId)
        val row = Sites.selectAll().where { (Sites.projectId eq projectId) and (Sites.serviceId eq serviceId) }.singleOrNull()
            ?: Sites.selectAll().where { (Sites.projectId eq projectId) and Sites.serviceId.isNull() }.singleOrNull()
            ?: return@transaction null
        val slug = Projects.selectAll().where { Projects.id eq projectId }.singleOrNull()?.get(Projects.slug)
        val serviceName = Services.selectAll().where { Services.id eq serviceId }.singleOrNull()?.get(Services.name)
        row.toRecord(if (serviceName == "default") slug else slug?.let { "$it-${row[Sites.domain].toSiteSlugSuffix()}" })
    }

    fun findDefaultServiceId(projectId: UUID): UUID = transaction { defaultServiceId(projectId) }

    fun findByServiceId(serviceId: UUID): SiteRecord? = transaction {
        val row = Sites.selectAll().where { Sites.serviceId eq serviceId }.singleOrNull() ?: return@transaction null
        val projectId = row[Sites.projectId]
        val projectSlug = Projects.selectAll().where { Projects.id eq projectId }.singleOrNull()?.get(Projects.slug)
        val serviceName = Services.selectAll().where { Services.id eq serviceId }.singleOrNull()?.get(Services.name)
        val domainSlug = row[Sites.domain].toSiteSlugSuffix()
        row.toRecord(if (serviceName == "default") projectSlug else projectSlug?.let { "$it-$domainSlug" })
    }

    fun findGateSlugsByServiceId(serviceId: UUID): List<String> = transaction {
        val service = Services.selectAll().where { Services.id eq serviceId }.singleOrNull()
            ?: return@transaction emptyList()
        val projectSlug = Projects.selectAll().where { Projects.id eq service[Services.projectId] }
            .singleOrNull()?.get(Projects.slug) ?: return@transaction emptyList()
        val isDefault = service[Services.name] == "default"
        val siteSlugs = Sites.selectAll().where { Sites.serviceId eq serviceId }.map { row ->
            if (isDefault) projectSlug else "$projectSlug-${row[Sites.domain].toSiteSlugSuffix()}"
        }
        (if (isDefault) listOf(projectSlug) else emptyList()) + siteSlugs
    }

    fun findGateSlugsByProjectId(projectId: UUID): List<String> = transaction {
        val projectSlug = Projects.selectAll().where { Projects.id eq projectId }
            .singleOrNull()?.get(Projects.slug) ?: return@transaction emptyList()
        Sites.selectAll().where { Sites.projectId eq projectId }.map { row ->
            val serviceId = row[Sites.serviceId]
            val isDefault = serviceId?.let { id ->
                Services.selectAll().where { (Services.id eq id) and (Services.name eq "default") }
                    .count() == 1L
            } ?: true
            if (isDefault) projectSlug else "$projectSlug-${row[Sites.domain].toSiteSlugSuffix()}"
        }.distinct()
    }

    fun findByDomain(domain: String): SiteRecord? = transaction {
        val row = Sites.selectAll().where { Sites.domain.lowerCase() eq domain.lowercase() }.singleOrNull() ?: return@transaction null
        val projectId = row[Sites.projectId]
        val projectSlug = Projects.selectAll().where { Projects.id eq projectId }.singleOrNull()?.get(Projects.slug)
        val serviceId = row[Sites.serviceId]
        val serviceName = serviceId?.let { id -> Services.selectAll().where { Services.id eq id }.singleOrNull()?.get(Services.name) }
        val siteSlug = if (serviceName == "default") projectSlug else projectSlug?.let { "$it-${row[Sites.domain].toSiteSlugSuffix()}" }
        row.toRecord(siteSlug)
    }

    fun findByProjectIdAndServiceId(projectId: UUID, serviceId: UUID): SiteRecord? = transaction {
        val row = Sites.selectAll().where { (Sites.projectId eq projectId) and (Sites.serviceId eq serviceId) }.singleOrNull() ?: return@transaction null
        val projectSlug = Projects.selectAll().where { Projects.id eq projectId }.singleOrNull()?.get(Projects.slug)
        val serviceName = Services.selectAll().where { Services.id eq serviceId }.singleOrNull()?.get(Services.name)
        row.toRecord(if (serviceName == "default") projectSlug else projectSlug?.let { "$it-${row[Sites.domain].toSiteSlugSuffix()}" })
    }

    fun findById(id: UUID): SiteRecord? = transaction {
        val row = Sites.selectAll().where { Sites.id eq id }.singleOrNull() ?: return@transaction null
        val projectId = row[Sites.projectId]
        val projectSlug = Projects.selectAll().where { Projects.id eq projectId }.singleOrNull()?.get(Projects.slug)
        val serviceName = row[Sites.serviceId]?.let { id -> Services.selectAll().where { Services.id eq id }.singleOrNull()?.get(Services.name) }
        val domainSlug = row[Sites.domain].toSiteSlugSuffix()
        row.toRecord(if (serviceName == "default") projectSlug else projectSlug?.let { "$it-$domainSlug" })
    }

    fun findByProjectSlug(slug: String): SiteRecord? = transaction {
        Sites.innerJoin(Projects).selectAll()
            .where { Projects.deletedAt.isNull() }
            .toList()
            .firstOrNull { row ->
                val siteSlug = "${row[Projects.slug]}-${row[Sites.domain].toSiteSlugSuffix()}"
                val isDefault = row[Sites.serviceId]?.let { serviceId ->
                    Services.selectAll().where { (Services.id eq serviceId) and (Services.name eq "default") }.count() == 1L
                } ?: true
                (slug == siteSlug && !isDefault) || (slug == row[Projects.slug] && isDefault)
            }?.let { row ->
                val isDefault = row[Sites.serviceId]?.let { serviceId ->
                    Services.selectAll().where { (Services.id eq serviceId) and (Services.name eq "default") }.count() == 1L
                } ?: true
                val domainSuffix = row[Sites.domain].toSiteSlugSuffix()
                row.toRecord(if (isDefault) row[Projects.slug] else "${row[Projects.slug]}-$domainSuffix")
            }
    }

    fun findAll(): List<SiteRecord> = transaction {
        Sites.innerJoin(Projects).selectAll()
            .where { Projects.deletedAt.isNull() }
            .map { row ->
                val isDefault = row[Sites.serviceId]?.let { serviceId ->
                    Services.selectAll().where { (Services.id eq serviceId) and (Services.name eq "default") }.count() == 1L
                } ?: true
                val slug = if (isDefault) row[Projects.slug] else "${row[Projects.slug]}-${row[Sites.domain].toSiteSlugSuffix()}"
                row.toRecord(slug)
            }
    }

    fun findByProjectIds(projectIds: Collection<UUID>): Map<UUID, SiteRecord> = transaction {
        if (projectIds.isEmpty()) return@transaction emptyMap()
        Sites.selectAll().where { Sites.projectId inList projectIds }
            .groupBy { it[Sites.projectId] }
            .mapNotNull { (projectId, rows) ->
                val defaultId = defaultServiceId(projectId)
                val row = rows.firstOrNull { it[Sites.serviceId] == defaultId }
                    ?: rows.firstOrNull { it[Sites.serviceId] == null } ?: return@mapNotNull null
                val projectSlug = Projects.selectAll().where { Projects.id eq projectId }.singleOrNull()?.get(Projects.slug)
                projectId to row.toRecord(projectSlug)
            }.toMap()
    }

    fun updateReconciliation(
        id: UUID,
        status: ReconciliationStatus,
        nginxError: String?,
        dockerError: String?,
        reconciledAt: LocalDateTime = LocalDateTime.now()
    ) = transaction {
        Sites.update({ Sites.id eq id }) {
            it[Sites.reconciliationStatus] = status
            it[Sites.lastNginxError] = nginxError
            it[Sites.lastDockerError] = dockerError
            it[Sites.lastReconciledAt] = reconciledAt
        }
    }

    fun create(projectId: UUID, model: com.gatekeeper.nginx.NginxSiteRenderModel): SiteRecord = transaction {
        val id = UUID.randomUUID()
        val serviceId = model.serviceId ?: defaultServiceId(projectId)
        check(Services.selectAll().where { (Services.id eq serviceId) and (Services.projectId eq projectId) }.count() == 1L) {
            "Service does not belong to project"
        }
        val projectSlug = Projects.selectAll().where { Projects.id eq projectId }.singleOrNull()?.get(Projects.slug)
            ?: error("Project not found")
        val fileSlug = model.slug.takeIf { it != projectSlug } ?: "$projectSlug-${model.domain.toSiteSlugSuffix()}"
        Sites.insert {
            it[Sites.id] = id
            it[Sites.projectId] = projectId
            it[Sites.serviceId] = serviceId
            it[Sites.domain] = model.domain
            it[Sites.upstreamHost] = model.upstreamHost
            it[Sites.upstreamMode] = model.upstreamMode
            it[Sites.upstreamExplicitPort] = if (model.upstreamMode == UpstreamMode.EXPLICIT_PORT) model.appPort else null
            it[Sites.tlsMode] = when (model.tlsMode) {
                com.gatekeeper.nginx.TlsRenderMode.HTTP_ONLY -> TlsMode.HTTP_ONLY
                com.gatekeeper.nginx.TlsRenderMode.HTTPS -> TlsMode.HTTPS
                com.gatekeeper.nginx.TlsRenderMode.HTTPS_HTTP2 -> TlsMode.HTTPS_HTTP2
            }
            it[Sites.certMode] = model.certMode
            it[Sites.certExplicitPath] = model.certificatePath.takeIf { model.certMode == CertMode.EXPLICIT_PATH }
            it[Sites.gateEnabled] = model.gateEnabled
            it[Sites.bypassPaths] = Json.encodeToString(model.bypassPaths)
        }
        findById(id)!!.copy(projectSlug = fileSlug)
    }

    fun deleteByProjectId(projectId: UUID) = transaction {
        Sites.deleteWhere { Sites.projectId eq projectId }
    }

    fun deleteById(id: UUID) = transaction {
        Sites.deleteWhere { Sites.id eq id }
    }

    fun updateDashboard(id: UUID, update: SiteDashboardUpdate): SiteRecord? = transaction {
        val current = Sites.selectAll().where { Sites.id eq id }.singleOrNull() ?: return@transaction null
        val currentVersion = current[Sites.configVersion]
        val newDomain = update.domain ?: current[Sites.domain]
        Sites.update({ Sites.id eq id }) {
            update.domain?.let { value -> it[Sites.domain] = value }
            update.upstreamHost?.let { value -> it[Sites.upstreamHost] = value }
            update.upstreamMode?.let { value -> it[Sites.upstreamMode] = value }
            update.upstreamExplicitPort?.let { value -> it[Sites.upstreamExplicitPort] = value }
            update.tlsMode?.let { value -> it[Sites.tlsMode] = value }
            update.certMode?.let { value -> it[Sites.certMode] = value }
            update.certExplicitPath?.let { value -> it[Sites.certExplicitPath] = value }
            update.gateEnabled?.let { value -> it[Sites.gateEnabled] = value }
            update.bypassPaths?.let { value -> it[Sites.bypassPaths] = Json.encodeToString(value) }
            it[Sites.configVersion] = currentVersion + 1
            it[Sites.updatedAt] = LocalDateTime.now()
        }
        val row = Sites.selectAll().where { Sites.id eq id }.singleOrNull() ?: return@transaction null
        val projectSlug = Projects.selectAll().where { Projects.id eq row[Sites.projectId] }.singleOrNull()?.get(Projects.slug)
        val serviceName = row[Sites.serviceId]?.let { serviceId -> Services.selectAll().where { Services.id eq serviceId }.singleOrNull()?.get(Services.name) }
        val siteSlug = if (serviceName == "default" && newDomain == current[Sites.domain]) projectSlug
            else projectSlug?.let { "$it-${newDomain.toSiteSlugSuffix()}" }
        row.toRecord(siteSlug)
    }

    fun updateDeploymentUpstream(serviceId: UUID, host: String, port: Int): SiteRecord? = transaction {
        require(host == "127.0.0.1") { "Deployment upstream host must be loopback" }
        require(port in 1..65535) { "Deployment upstream port must be between 1 and 65535" }
        val site = Sites.selectAll().where { Sites.serviceId eq serviceId }.singleOrNull() ?: return@transaction null
        Sites.update({ Sites.id eq site[Sites.id] }) {
            it[Sites.upstreamHost] = host
            it[Sites.upstreamMode] = UpstreamMode.EXPLICIT_PORT
            it[Sites.upstreamExplicitPort] = port
            it[Sites.configVersion] = site[Sites.configVersion] + 1
            it[Sites.reconciliationStatus] = ReconciliationStatus.HEALTHY
            it[Sites.lastNginxError] = null
            it[Sites.lastDockerError] = null
            it[updatedAt] = LocalDateTime.now()
        }
        Sites.selectAll().where { Sites.id eq site[Sites.id] }.singleOrNull()?.toRecord()
    }

    fun updateDeploymentUpstreamForSite(siteId: UUID, host: String, port: Int): SiteRecord? = transaction {
        val serviceId = Sites.selectAll().where { Sites.id eq siteId }.singleOrNull()?.get(Sites.serviceId) ?: return@transaction null
        updateDeploymentUpstream(serviceId, host, port)
    }

    fun restoreDeploymentUpstream(serviceId: UUID, site: SiteRecord): SiteRecord? = transaction {
        val existing = Sites.selectAll().where { (Sites.id eq site.id) and (Sites.serviceId eq serviceId) }.singleOrNull() ?: return@transaction null
        Sites.update({ Sites.id eq site.id }) {
            it[Sites.upstreamHost] = site.upstreamHost
            it[Sites.upstreamMode] = site.upstreamMode
            it[Sites.upstreamExplicitPort] = site.upstreamExplicitPort
            it[Sites.configVersion] = existing[Sites.configVersion] + 1
            it[Sites.updatedAt] = LocalDateTime.now()
        }
        Sites.selectAll().where { Sites.id eq site.id }.singleOrNull()?.toRecord()
    }

    fun restoreDeploymentUpstreamForSite(site: SiteRecord): SiteRecord? = transaction {
        val existing = Sites.selectAll().where { Sites.id eq site.id }.singleOrNull() ?: return@transaction null
        Sites.update({ Sites.id eq site.id }) {
            it[Sites.upstreamHost] = site.upstreamHost
            it[Sites.upstreamMode] = site.upstreamMode
            it[Sites.upstreamExplicitPort] = site.upstreamExplicitPort
            it[Sites.configVersion] = existing[Sites.configVersion] + 1
            it[Sites.updatedAt] = LocalDateTime.now()
        }
        Sites.selectAll().where { Sites.id eq site.id }.singleOrNull()?.toRecord()
    }

    data class SiteDashboardUpdate(
        val domain: String? = null, val upstreamHost: String? = null, val upstreamMode: UpstreamMode? = null,
        val upstreamExplicitPort: Int? = null,
        val tlsMode: TlsMode? = null, val certMode: CertMode? = null, val certExplicitPath: String? = null,
        val gateEnabled: Boolean? = null, val bypassPaths: List<String>? = null
    )

    fun linkCertificateForDomain(domain: String, certificateId: UUID) = transaction {
        Sites.update({ Sites.domain eq domain }) { it[Sites.certificateId] = certificateId }
    }

    fun linkCertificate(projectId: UUID, certificateId: UUID) = transaction {
        Sites.update({ Sites.projectId eq projectId }) { it[Sites.certificateId] = certificateId }
    }

    private fun ResultRow.toRecord(projectSlug: String? = null) = SiteRecord(
        this[Sites.id], this[Sites.projectId], this[Sites.domain], this[Sites.upstreamHost],
        this[Sites.upstreamMode], this[Sites.upstreamExplicitPort],
        this[Sites.tlsMode], this[Sites.certMode], this[Sites.certExplicitPath], this[Sites.gateEnabled],
        this[Sites.configVersion], this[Sites.createdAt], this[Sites.updatedAt],
        runCatching { Json.decodeFromString<List<String>>(this[Sites.bypassPaths]) }.getOrDefault(DEFAULT_GATEKEEPER_BYPASS_PATHS),
        this[Sites.reconciliationStatus],
        this[Sites.lastNginxError], this[Sites.lastDockerError], this[Sites.lastReconciledAt], projectSlug,
        this[Sites.serviceId]
    )
}

private fun String.toSiteSlugSuffix(): String =
    lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifBlank { "site" }
