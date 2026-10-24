package com.gatekeeper.feature.service.domain.repository

import com.gatekeeper.db.tables.ReconciliationStatus
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import java.time.LocalDateTime
import java.util.UUID

interface SiteRepository{

    fun existsForProject(projectId: UUID): Boolean

    fun existsForService(serviceId: UUID): Boolean

    fun existsForDomain(projectId: UUID, domain: String): Boolean

    fun findByProjectId(projectId: UUID): SiteRecord?

    fun findDefaultServiceId(projectId: UUID): UUID

    fun findByServiceId(serviceId: UUID): SiteRecord?

    fun findAllByServiceId(serviceId: UUID): List<SiteRecord>

    fun findByServiceAndPort(serviceId: UUID, containerPort: Int): SiteRecord?

    fun findGateSlugsByServiceId(serviceId: UUID): List<String>

    fun findGateSlugsByProjectId(projectId: UUID): List<String>

    fun findByDomain(domain: String): SiteRecord?

    fun findByProjectIdAndServiceId(projectId: UUID, serviceId: UUID): SiteRecord?

    fun findById(id: UUID): SiteRecord?

    fun findByProjectSlug(slug: String): SiteRecord?

    fun findAll(): List<SiteRecord>

    fun findByProjectIds(projectIds: Collection<UUID>): Map<UUID, SiteRecord>

    fun updateReconciliation(
        id: UUID,
        status: ReconciliationStatus,
        nginxError: String?,
        dockerError: String?,
        reconciledAt: LocalDateTime = LocalDateTime.now()
    ): Int

    fun create(projectId: UUID, model: NginxSiteRenderModel, containerPort: Int? = null): SiteRecord

    fun deleteByProjectId(projectId: UUID): Int

    fun deleteById(id: UUID): Int

    fun updateDashboard(id: UUID, update: SiteDashboardUpdate): SiteRecord?

    fun setUpstreamKind(id: UUID, kind: String): SiteRecord?

    fun restoreUpstreamKind(id: UUID, kind: String?): SiteRecord?

    fun updateDeploymentUpstream(serviceId: UUID, host: String, port: Int): SiteRecord?

    fun updateDeploymentUpstreamForSite(siteId: UUID, host: String, port: Int, containerPort: Int? = null): SiteRecord?

    fun restoreDeploymentUpstream(serviceId: UUID, site: SiteRecord): SiteRecord?

    fun restoreDeploymentUpstreamForSite(site: SiteRecord): SiteRecord?

    fun linkCertificateForDomain(domain: String, certificateId: UUID): Int

    fun linkCertificate(projectId: UUID, certificateId: UUID): Int
}
