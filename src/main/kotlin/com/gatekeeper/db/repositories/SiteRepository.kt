package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.CertMode
import com.gatekeeper.db.tables.Projects
import com.gatekeeper.db.tables.Sites
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
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import com.gatekeeper.nginx.DEFAULT_GATEKEEPER_BYPASS_PATHS

object SiteRepository {
    data class SiteRecord(
        val id: UUID, val projectId: UUID, val domain: String, val upstreamHost: String,
        val upstreamMode: UpstreamMode, val upstreamContainerName: String?, val upstreamExplicitPort: Int?,
        val tlsMode: TlsMode, val certMode: CertMode, val certExplicitPath: String?, val gateEnabled: Boolean,
        val configVersion: Int, val createdAt: LocalDateTime, val updatedAt: LocalDateTime,
        val bypassPaths: List<String> = DEFAULT_GATEKEEPER_BYPASS_PATHS,
        val reconciliationStatus: ReconciliationStatus, val lastNginxError: String?, val lastDockerError: String?, val lastReconciledAt: LocalDateTime?, val projectSlug: String? = null
    )

    fun findByProjectId(projectId: UUID): SiteRecord? = transaction {
        Sites.selectAll().where { Sites.projectId eq projectId }.singleOrNull()?.toRecord()
    }

    fun findByProjectSlug(slug: String): SiteRecord? = transaction {
        Sites.innerJoin(Projects).selectAll()
            .where { (Projects.slug eq slug) and Projects.deletedAt.isNull() }
            .singleOrNull()?.toRecord(slug)
    }

    fun findAll(): List<SiteRecord> = transaction {
        Sites.innerJoin(Projects).selectAll()
            .where { Projects.deletedAt.isNull() }
            .map { it.toRecord(it[Projects.slug]) }
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
        Sites.insert {
            it[Sites.id] = id
            it[Sites.projectId] = projectId
            it[Sites.domain] = model.domain
            it[Sites.upstreamHost] = model.upstreamHost
            it[Sites.upstreamMode] = model.upstreamMode
            it[Sites.upstreamContainerName] = model.upstreamContainerName
            it[Sites.upstreamExplicitPort] = if (model.upstreamMode == UpstreamMode.EXPLICIT_PORT) model.appPort else null
            it[Sites.tlsMode] = when (model.tlsMode) {
                com.gatekeeper.nginx.TlsRenderMode.HTTP_ONLY -> TlsMode.HTTP_ONLY
                com.gatekeeper.nginx.TlsRenderMode.HTTPS -> TlsMode.HTTPS
                com.gatekeeper.nginx.TlsRenderMode.HTTPS_HTTP2 -> TlsMode.HTTPS_HTTP2
            }
            it[Sites.certMode] = model.certMode
            it[Sites.certExplicitPath] = model.certificatePath
            it[Sites.gateEnabled] = model.gateEnabled
            it[Sites.bypassPaths] = Json.encodeToString(model.bypassPaths)
        }
        findByProjectId(projectId)!!
    }

    fun deleteByProjectId(projectId: UUID) = transaction {
        Sites.deleteWhere { Sites.projectId eq projectId }
    }

    fun updateDashboard(id: UUID, update: SiteDashboardUpdate): SiteRecord? = transaction {
        val currentVersion = Sites.selectAll().where { Sites.id eq id }.singleOrNull()?.get(Sites.configVersion) ?: return@transaction null
        Sites.update({ Sites.id eq id }) {
            update.domain?.let { value -> it[Sites.domain] = value }
            update.upstreamHost?.let { value -> it[Sites.upstreamHost] = value }
            update.upstreamMode?.let { value -> it[Sites.upstreamMode] = value }
            update.upstreamContainerName?.let { value -> it[Sites.upstreamContainerName] = value }
            update.upstreamExplicitPort?.let { value -> it[Sites.upstreamExplicitPort] = value }
            update.tlsMode?.let { value -> it[Sites.tlsMode] = value }
            update.certMode?.let { value -> it[Sites.certMode] = value }
            update.certExplicitPath?.let { value -> it[Sites.certExplicitPath] = value }
            update.gateEnabled?.let { value -> it[Sites.gateEnabled] = value }
            update.bypassPaths?.let { value -> it[Sites.bypassPaths] = Json.encodeToString(value) }
            it[Sites.configVersion] = currentVersion + 1
            it[Sites.updatedAt] = LocalDateTime.now()
        }
        Sites.selectAll().where { Sites.id eq id }.singleOrNull()?.toRecord()
    }

    data class SiteDashboardUpdate(
        val domain: String? = null, val upstreamHost: String? = null, val upstreamMode: UpstreamMode? = null,
        val upstreamContainerName: String? = null, val upstreamExplicitPort: Int? = null,
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
        this[Sites.upstreamMode], this[Sites.upstreamContainerName], this[Sites.upstreamExplicitPort],
        this[Sites.tlsMode], this[Sites.certMode], this[Sites.certExplicitPath], this[Sites.gateEnabled],
        this[Sites.configVersion], this[Sites.createdAt], this[Sites.updatedAt],
        runCatching { Json.decodeFromString<List<String>>(this[Sites.bypassPaths]) }.getOrDefault(DEFAULT_GATEKEEPER_BYPASS_PATHS),
        this[Sites.reconciliationStatus],
        this[Sites.lastNginxError], this[Sites.lastDockerError], this[Sites.lastReconciledAt], projectSlug
    )
}
