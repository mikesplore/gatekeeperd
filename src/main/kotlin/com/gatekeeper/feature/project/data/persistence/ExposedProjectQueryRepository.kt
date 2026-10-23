package com.gatekeeper.feature.project.data.persistence

import com.gatekeeper.feature.project.domain.model.ProjectRecord
import com.gatekeeper.feature.project.domain.repository.GateTarget
import com.gatekeeper.feature.project.domain.repository.ProjectQueryRepository
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import com.gatekeeper.feature.service.domain.repository.SiteRepository

class ExposedProjectQueryRepository(private val projects: ProjectRepository, private val siteRepository: SiteRepository, private val serviceRepository: ServiceRepository) :
    ProjectQueryRepository {
    override fun findBySlug(slug: String) = projects.findBySlug(slug)

    override fun findGateTargetBySlug(slug: String): GateTarget? {
        val site = siteRepository.findByProjectSlug(slug)
        val project = site?.let { projects.findById(it.projectId) }
            ?: projects.findBySlug(slug)
            ?: return null
        return gateTarget(project, site)
    }

    override fun findGateTargetByDomain(domain: String): GateTarget? {
        val normalizedDomain = domain.trim().lowercase().substringBefore(':').trimEnd('.')
        if (normalizedDomain.isBlank()) return null
        val site = siteRepository.findByDomain(normalizedDomain) ?: return null
        val project = projects.findById(site.projectId) ?: return null
        return gateTarget(project, site)
    }

    private fun gateTarget(project: ProjectRecord, site: SiteRecord?): GateTarget? {
        val serviceId = site?.serviceId ?: siteRepository.findDefaultServiceId(project.id)
        val service = serviceRepository.findAccessById(serviceId) ?: return null
        return GateTarget(
            project = project,
            serviceId = service.id,
            siteDomain = site?.domain ?: project.domain,
            siteSlug = site?.projectSlug ?: project.slug,
            serviceName = service.name,
            serviceAccessStatus = service.accessStatus,
            serviceBlockReason = service.blockReason,
            serviceBlockReasonCode = service.blockReasonCode,
            serviceBlockReasonNote = service.blockReasonNote
        )
    }
}