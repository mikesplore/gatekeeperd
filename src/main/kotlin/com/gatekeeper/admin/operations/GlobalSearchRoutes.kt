package com.gatekeeper.admin.operations

import com.gatekeeper.feature.customer.domain.repository.CustomerRepository
import com.gatekeeper.feature.deployment.domain.repository.ServiceDomainRepository
import com.gatekeeper.feature.nginx.application.ListConfigArtifactsUseCase
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.service.domain.repository.ServiceRepository
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.rbac.application.RbacApplicationService
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.get

fun Route.registerGlobalSearchRoutes() {
    get("/api/admin/search") {
        val query = call.request.queryParameters["q"].orEmpty().trim().take(100)
        if (query.length < 2) {
            call.respond(GlobalSearchResponse(query, emptyList()))
            return@get
        }
        val subject = call.principal<JWTPrincipal>()?.payload?.subject.orEmpty()
        val rbac = call.application.get<RbacApplicationService>()
        suspend fun allowed(permission: String) = runCatching { rbac.authorize(subject, permission) }.getOrDefault(false)

        val canProjects = allowed("projects.read")
        val canCustomers = allowed("customers.read")
        val canServices = allowed("services.read")
        val canNginx = allowed("nginx.read")
        val normalized = query.lowercase()
        val results = mutableListOf<GlobalSearchItem>()

        val projects = if (canProjects || canServices || canNginx) {
            call.application.get<ProjectRepository>().findAll(includeArchived = true)
        } else emptyList()
        val projectById = projects.associateBy { it.id }

        if (canCustomers) {
            call.application.get<CustomerRepository>().findPage(query, 8, 0).first.forEach { customer ->
                results += GlobalSearchItem(
                    category = "Customers",
                    kind = "customer",
                    title = customer.name,
                    subtitle = listOfNotNull(customer.contactEmail, customer.contactPhone).joinToString(" · ").ifBlank { "Customer profile" },
                    href = "/app/customers/${customer.id}"
                )
            }
        }

        if (canProjects) {
            projects.filter { project ->
                listOf(project.name, project.slug, project.domain, project.customerName.orEmpty())
                    .any { it.contains(normalized, ignoreCase = true) }
            }.take(8).forEach { project ->
                results += GlobalSearchItem("Projects", "project", project.name, "${project.slug} · ${project.domain}", "/app/projects/${project.slug}")
                if (project.domain.contains(normalized, ignoreCase = true)) {
                    results += GlobalSearchItem("Domains", "domain", project.domain, "Project domain · ${project.name}", "/app/projects/${project.slug}")
                }
            }
        }

        val services = if (canServices || canNginx) call.application.get<ServiceRepository>().listAll() else emptyList()
        val serviceById = services.associateBy { it.id }
        if (canServices) {
            services.filter { service ->
                val project = projectById[service.projectId]
                listOf(service.name, service.domain.orEmpty(), project?.name.orEmpty(), project?.slug.orEmpty())
                    .any { it.contains(normalized, ignoreCase = true) }
            }.take(8).forEach { service ->
                val project = projectById[service.projectId]
                results += GlobalSearchItem(
                    "Services", "service", service.name,
                    listOfNotNull(project?.name, service.domain).joinToString(" · ").ifBlank { service.serviceType },
                    project?.let { "/app/projects/${it.slug}/services/${service.id}" } ?: "/app/projects"
                )
            }
        }

        if (canServices || canNginx) {
            val domains = call.application.get<ServiceDomainRepository>()
            services.forEach { service ->
                val project = projectById[service.projectId]
                val href = if (canServices) {
                    project?.let { "/app/projects/${it.slug}/services/${service.id}" } ?: "/app/projects"
                } else "/app/nginx"
                val serviceDomains = domains.listByService(service.id)
                (serviceDomains.map { it.domain to "Service binding" } +
                    listOfNotNull(service.domain?.let { it to "Service domain" }))
                    .filter { (domain, _) -> domain.contains(normalized, ignoreCase = true) }
                    .groupBy({ it.first }, { it.second })
                    .forEach { (domain, sources) ->
                        val sourceLabel = sources.distinct().joinToString(" + ")
                        results += GlobalSearchItem("Domains", "domain", domain, listOfNotNull(sourceLabel, service.name, project?.name).joinToString(" · "), href)
                    }
            }
        }

        if (canNginx) {
            val siteRepository = call.application.get<SiteRepository>()
            val sites = siteRepository.findAll()
            sites.filter { it.domain.contains(normalized, ignoreCase = true) }.forEach { site ->
                val project = projectById[site.projectId]
                results += GlobalSearchItem(
                    "Domains", "domain", site.domain,
                    "Nginx site${project?.name?.let { " · $it" }.orEmpty()}",
                    "/app/nginx/sites/${site.projectSlug ?: site.domain}"
                )
            }
            call.application.get<ListConfigArtifactsUseCase>().execute(sites)
                .filter { artifact -> artifact.domains.any { it.contains(normalized, ignoreCase = true) } }
                .forEach { artifact ->
                    artifact.domains.filter { it.contains(normalized, ignoreCase = true) }.forEach { domain ->
                        results += GlobalSearchItem(
                            "Domains", "domain", domain,
                            "Nginx config · ${artifact.filename}${if (artifact.orphaned) " · Orphaned" else ""}",
                            "/app/nginx/dead-configs"
                        )
                    }
                }
        }

        val grouped = results.distinctBy { "${it.category}:${it.title.lowercase()}:${it.subtitle}:${it.href}" }
            .groupBy { it.category }
            .values.flatMap { it.take(8) }
        call.respond(GlobalSearchResponse(query, grouped))
    }
}

@Serializable
data class GlobalSearchResponse(val query: String, val results: List<GlobalSearchItem>)

@Serializable
data class GlobalSearchItem(
    val category: String,
    val kind: String,
    val title: String,
    val subtitle: String,
    val href: String
)
