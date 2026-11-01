package com.gatekeeper.admin.application


import com.gatekeeper.admin.model.*
import com.gatekeeper.config.AppSettings
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.core.ports.PortProbe
import com.gatekeeper.db.tables.UpstreamMode
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamResolver
import com.gatekeeper.feature.nginx.infrastructure.NginxSelfDomainExclusion
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.sql.SQLException
import java.util.*
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository as CustomerStore
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import com.gatekeeper.feature.service.domain.repository.SiteRepository

object OperationsDashboardService {
    fun dashboardUpstreamState(deploymentUpstreamResolver: DeploymentUpstreamResolver, site: SiteRecord, dockerService: DockerGateway?, portProbe: PortProbe): String {
        val port = when (site.upstreamMode) {
           UpstreamMode.EXPLICIT_PORT -> site.upstreamExplicitPort ?: return "unknown"
           UpstreamMode.DOCKER_DISCOVERY -> {
                val target = site.serviceId?.let {
                    site.upstreamContainerPort?.let { containerPort ->
                        deploymentUpstreamResolver.resolve(it, containerPort, "production")
                    } ?: deploymentUpstreamResolver.resolve(it, "production")
                } ?: return "unknown"
                val docker = dockerService ?: return "unknown"
                val name = target.containerName ?: return "unknown"
                val container = runCatching { docker.getContainer(name) }.getOrNull() ?: return "down"
                if (!container.state.equals("running", ignoreCase = true)) return "down"
                target.port
            }
        }
        val reachable = portProbe.isReachable(site.upstreamHost, port, 500)
        return if (reachable) "running" else "down"
    }

    fun isUniqueConstraintViolation(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .filterIsInstance<SQLException>()
        .any { it.sqlState == "23505" }


    fun customerOwnedProjects(projectRepository: ProjectRepository,siteRepository: SiteRepository, customerId: UUID): List<CustomerOwnedProject> {
        val projects = projectRepository.findByCustomerId(customerId)
        val sites = siteRepository.findByProjectIds(projects.map { it.id })
        return projects.map { project -> CustomerOwnedProject(project, sites[project.id]) }
    }

    fun dashboardSite(
        site: SiteRecord,
        projectRepository: ProjectRepository,
        customerStore: CustomerStore,
        nginxSettings: AppSettings.NginxSettings,
        deploymentUpstreamResolver: DeploymentUpstreamResolver,
        dockerService: DockerGateway? = null
    ): DashboardSiteResponse {
        val project = projectRepository.findById(site.projectId)
        val customer = project?.customerId?.let(customerStore::findById)
        val slug = site.projectSlug ?: site.projectId.toString()
        val availableFile = File(nginxSettings.sitesAvailablePath, slug)
        val enabledFile = File(nginxSettings.sitesEnabledPath, slug)
        val selfDomainConfig = NginxSelfDomainExclusion(nginxSettings.selfDomains).let { exclusion ->
            exclusion.excludesFile(availableFile) || exclusion.excludesFile(enabledFile)
        }
        val resolved = site.serviceId?.let {
            site.upstreamContainerPort?.let { containerPort ->
                deploymentUpstreamResolver.resolve(it, containerPort, "production")
            } ?: deploymentUpstreamResolver.resolve(it, "production")
        }
        val configuredHost = site.upstreamHost
        val configuredPort = site.upstreamExplicitPort
        val displayHost =
            if (site.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.EXPLICIT_PORT) configuredHost else resolved?.host
                ?: configuredHost
        val displayPort =
            if (site.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.EXPLICIT_PORT) configuredPort else resolved?.port
        return DashboardSiteResponse(
            slug, site.projectId.toString(), customer?.id?.toString(), customer?.name, site.domain,
            site.reconciliationStatus.value,
            site.lastNginxError, site.lastDockerError, site.configVersion,
            !selfDomainConfig && availableFile.isFile,
            !selfDomainConfig && java.nio.file.Files.isSymbolicLink(enabledFile.toPath()),
            displayHost, site.upstreamMode.value, displayPort,
            if (site.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.DOCKER_DISCOVERY) {
                val name = resolved?.containerName
                if (name == null || dockerService == null) null else runCatching { dockerService.containerHealth(name) }.getOrNull()
            } else null,
            if (site.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.EXPLICIT_PORT) configuredHost else resolved?.host,
            if (site.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.EXPLICIT_PORT) configuredPort else resolved?.port,
            site.tlsMode.value, site.gateEnabled, site.serviceId?.toString(), site.upstreamKind
        )
    }

    inline fun <T> withDashboardDocker(
        docker: DockerGateway?,
        sites: List<SiteRecord>,
        block: (DockerGateway?) -> T
    ): T {
        val needsDocker = sites.any { it.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.DOCKER_DISCOVERY }
        return block(docker.takeIf { needsDocker })
    }


    fun dashboardProjectFinancials(
        project: ProjectRecord,
        balances: ProjectBalanceAdapter
    ): DashboardProjectFinancials {
        val financials = balances.financials(project)
        val billed = financials.originalCharge + financials.additionalCharges - financials.discounts
        return DashboardProjectFinancials(billed, financials.paid, financials.outstanding)
    }

    fun derivedBillingStatus(financials: List<DashboardProjectFinancials>): String = when {
        financials.isEmpty() -> "unknown"
        financials.sumOf { it.balance } > java.math.BigDecimal.ZERO -> "overdue"
        else -> "current"
    }


}
