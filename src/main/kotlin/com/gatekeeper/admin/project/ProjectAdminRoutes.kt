package com.gatekeeper.admin.project


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.api.dto.AuditLogPageResponse
import com.gatekeeper.api.dto.ProjectDetailResponse
import com.gatekeeper.api.dto.OverdueProjectResponse
import com.gatekeeper.api.dto.ProjectsListResponse
import com.gatekeeper.api.dto.StatusChangeResponse
import com.gatekeeper.api.dto.toResponse
import com.gatekeeper.api.InputValidators
import com.gatekeeper.config.AppSettings
import com.gatekeeper.api.respondError
import com.gatekeeper.api.respondErrorWithData
import com.gatekeeper.feature.audit.domain.repository.AuditRepository as AuditRepositoryPort
import com.gatekeeper.feature.payment.domain.usecase.ListProjectPayments
import com.gatekeeper.feature.project.domain.repository.ProjectRepository
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository as CustomerStore
import com.gatekeeper.feature.project.domain.repository.ProjectAdjustmentRepository as ProjectAdjustmentStore
import com.gatekeeper.db.tables.AdjustmentType
import com.gatekeeper.db.tables.AccessBlockReason
import com.gatekeeper.integrations.ScribedIntegrationClient
import com.gatekeeper.docker.ContainerCreatePlanResult
import com.gatekeeper.docker.DockerGateway
import com.gatekeeper.docker.ContainerWizardContextResponse
import com.gatekeeper.docker.CreateContainerRequest
import com.gatekeeper.docker.ContainerWizardValidateResponse
import com.gatekeeper.docker.DockerWizardInspect
import com.gatekeeper.docker.computeContainerCreatePlan
import com.gatekeeper.docker.DeleteImageRequest
import com.gatekeeper.docker.DeleteImageResponse
import com.gatekeeper.docker.ContainerCreationTracker
import com.gatekeeper.docker.ImageStatusRequest
import com.gatekeeper.docker.ImageStatusResponse
import com.gatekeeper.docker.PortsAvailabilityRequest
import com.gatekeeper.docker.PortsAvailabilityResponse
import com.gatekeeper.docker.parseImageRef
import com.gatekeeper.feature.nginx.application.RemoveSiteConfigUseCase
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import org.koin.ktor.ext.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.math.BigDecimal












fun Application.configureProjectAdminRoutes() {
    val projects = get<ProjectRepository>()
    val auditRepository = get<AuditRepositoryPort>()
    val scribed = get<ScribedIntegrationClient>()
    val nginx = get<NginxSiteStorage>()
    val removeSiteConfig = get<RemoveSiteConfigUseCase>()
    routing {
        authenticate("auth-jwt") {
            install(com.gatekeeper.rbac.presentation.RbacAuthorizationPlugin)
            get("/api/admin/projects") {
                val search = call.request.queryParameters["search"]?.trim()?.lowercase()
                val status = call.request.queryParameters["status"]
                val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 500
                val filteredProjects = projects.findAll().asSequence()
                    .filter { search.isNullOrBlank() || listOf(it.name, it.slug, it.domain, it.customerName ?: "").any { value -> value.lowercase().contains(search) } }
                    .filter { status.isNullOrBlank() || it.status == status }
                    .toList()
                call.respond(
                    ProjectsListResponse(
                        projects = filteredProjects.drop(offset).take(limit).map { project ->
                            project.toResponse(call.application.get<ProjectBalanceAdapter>().financials(project))
                        },
                        total = filteredProjects.size,
                        limit = limit,
                        offset = offset
                    )
                )
            }

            get("/api/admin/projects/overdue") {
                val overdue = projects.findOverdue(java.time.LocalDate.now()).map { item ->
                    OverdueProjectResponse(
                        slug = item.slug, name = item.name, customerName = item.customerName,
                        customerEmail = item.customerEmail, dueDate = item.dueDate.toString(),
                        daysOverdue = item.daysOverdue, gracePeriodDays = item.gracePeriodDays,
                        willAutoBlockOn = item.willAutoBlockOn.toString(), amountDue = item.amountDue?.toDouble() ?: 0.0
                    )
                }
                call.respond(overdue)
            }

            get("/api/admin/projects/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@get
                }
                val project = projects.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@get
                }
                val payments = call.application.get<ListProjectPayments>()(
                    ListProjectPayments.Command(project.slug, 500, 0)
                ).getOrElse {
                    call.respondError(HttpStatusCode.InternalServerError, "payment_list_failed", "Unable to load project payments")
                    return@get
                }.items
                val adjustments = call.application.get<ProjectAdjustmentStore>().findByProjectId(project.id)
                val auditLog = auditRepository.findByProjectId(project.id)
                call.respond(
                    ProjectDetailResponse(
                        project = project.toResponse(call.application.get<ProjectBalanceAdapter>().financials(project)),
                        payments = payments.map { it.toResponse() },
                        audit_log = auditLog.map { it.toResponse() },
                        adjustments = adjustments.map { it.toResponse() }
                    )
                )
            }

            patch("/api/admin/projects/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@patch
                }

                val body = try {
                    call.receive<UpdateProjectRequest>()
                } catch (e: Exception) {
                    projectAdminLogger.warn("Failed to parse update project request", e)
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "The request body could not be parsed")
                    return@patch
                }

                val dueDate = InputValidators.parseDueDate(body.dueDate.value)
                if (body.dueDate.value?.isNotBlank() == true && dueDate == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "dueDate must be ISO format YYYY-MM-DD")
                    return@patch
                }
                val amountDue = body.amountDue.value?.let { BigDecimal.valueOf(it) }

                val customerId = body.customerId.value?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                        ?: return@patch call.respondError(HttpStatusCode.BadRequest, "invalid_customer", "customerId must be a valid UUID")
                }
                if (customerId != null && call.application.get<CustomerStore>().findById(customerId) == null) {
                    call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found")
                    return@patch
                }

                if ((body.deploymentMode != null && body.deploymentMode !in ProjectAdminRules.deploymentModes) ||
                    (body.serviceMode != null && body.serviceMode !in ProjectAdminRules.serviceModes) ||
                    (body.lifecycleStatus != null && body.lifecycleStatus !in ProjectAdminRules.lifecycleStatuses)
                ) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid deployment, service, or lifecycle mode")
                    return@patch
                }

                if (body.lifecycleStatus != null) {
                    val current = projects.findBySlug(slug)
                    if (current == null) {
                        call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                        return@patch
                    }
                    if (body.lifecycleStatus != current.lifecycleStatus) {
                        call.respondError(
                            HttpStatusCode.Conflict,
                            "invalid_lifecycle_transition",
                            "Use the dedicated transfer or archive operation for lifecycle changes"
                        )
                        return@patch
                    }
                }

                val project = projects.update(
                    slug = slug,
                    name = body.name?.trim(),
                    domain = body.domain?.trim(),
                    billingName = body.billingName?.trim(),
                    billingEmail = body.billingEmail?.trim(),
                    billingAddress = body.billingAddress?.trim(),
                    amountDue = amountDue,
                    currency = body.currency,
                    dueDate = dueDate,
                    gracePeriodDays = body.gracePeriodDays,
                    clearAmountDue = body.amountDue.isSpecified && body.amountDue.value == null,
                    clearDueDate = body.dueDate.isSpecified && body.dueDate.value == null,
                    deploymentMode = body.deploymentMode,
                    serviceMode = body.serviceMode,
                    lifecycleStatus = body.lifecycleStatus
                )

                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@patch
                }

                val assignedProject = if (body.customerId.isSpecified) {
                    projects.assignCustomer(project.id, customerId)
                } else project

                projects.invalidateCache(slug)
                projectAdminLogger.info("Project updated: $slug")
                val responseProject = assignedProject ?: project
                call.respond(responseProject.toResponse(call.application.get<ProjectBalanceAdapter>().financials(responseProject)))
            }

            post("/api/admin/projects/{slug}/adjustments") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }
                val body = runCatching { call.receive<CreateProjectAdjustmentRequest>() }.getOrElse {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid adjustment request")
                    return@post
                }
                val type = runCatching { AdjustmentType.valueOf(body.type.trim().uppercase()) }.getOrNull()
                if (type == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_type", "type must be ADDITIONAL_CHARGE or DISCOUNT")
                    return@post
                }
                val amount = runCatching { BigDecimal.valueOf(body.amount) }.getOrNull()
                if (amount == null || amount <= BigDecimal.ZERO) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_amount", "amount must be greater than zero")
                    return@post
                }
                if (body.reason.isBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment_reason", "reason is required")
                    return@post
                }
                val project = projects.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@post
                }
                val projectBalances = call.application.get<ProjectBalanceAdapter>()
                val oldBalance = projectBalances.outstandingBalance(project)
                val actor = call.principal<JWTPrincipal>()?.payload?.subject ?: "unknown"
                val adjustment = try {
                    call.application.get<ProjectAdjustmentStore>().create(project.id, type, amount, body.reason, actor)
                } catch (e: IllegalArgumentException) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_adjustment", e.message ?: "Invalid adjustment")
                    return@post
                }
                val newBalance = projectBalances.outstandingBalance(project)
                auditRepository.write(
                    project.id,
                    "project_adjustment",
                    actor,
                    "${type.name} amount=$amount oldBalance=$oldBalance newBalance=$newBalance reason=${body.reason.trim()}"
                )
                projects.invalidateCache(slug)
                call.respond(ProjectAdjustmentResponse(adjustment.id.toString(), project.id.toString(), type.name, amount.toDouble(), adjustment.reason, actor, adjustment.createdAt.toString(), oldBalance.toDouble(), newBalance.toDouble()))
            }

            post("/api/admin/projects/{slug}/transfer") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }
                val body = try {
                    call.receive<TransferProjectRequest>()
                } catch (_: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid transfer request")
                    return@post
                }
                if (body.deploymentMode !in ProjectAdminRules.deploymentModes || body.serviceMode !in ProjectAdminRules.serviceModes) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid deployment or service mode")
                    return@post
                }
                val current = projects.findBySlug(slug)
                if (current == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@post
                }
                if (current.lifecycleStatus !in setOf("active", "transferred")) {
                    call.respondError(HttpStatusCode.Conflict, "invalid_lifecycle_transition", "Only active or transferred projects can be transferred")
                    return@post
                }
                val principal = call.principal<io.ktor.server.auth.jwt.JWTPrincipal>()
                val actor = principal?.payload?.subject ?: "unknown"
                val updated = projects.transfer(
                    slug = slug,
                    deploymentMode = body.deploymentMode,
                    serviceMode = body.serviceMode,
                    actor = actor,
                    reason = "Transferred via admin API"
                ) ?: current
                call.respond(updated.toResponse(call.application.get<ProjectBalanceAdapter>().financials(updated)))
            }

            delete("/api/admin/projects/{slug}") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@delete
                }

                val principal = call.principal<JWTPrincipal>()
                val actor = principal?.payload?.subject ?: "unknown"

                val archived = projects.archive(
                    slug = slug,
                    actor = actor,
                    reason = "Archived via admin API"
                )
                if (!archived) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@delete
                }

                runCatching {
                    val removed = removeSiteConfig.removeProject(slug)
                    if (!removed) {
                        projectAdminLogger.warn("Project archived but nginx cleanup failed for slug=$slug (removeProject returned false)")
                        return@runCatching
                    }
                    val reloaded = nginx.reloadNginx()
                    if (!reloaded) {
                        projectAdminLogger.warn("Project archived but nginx cleanup reload failed for slug=$slug")
                    }
                }.onFailure { e ->
                    projectAdminLogger.warn("Project archived but nginx cleanup errored for slug=$slug: ${e.message}")
                }

                projectAdminLogger.info("Project archived: $slug by $actor")
                call.respond(HttpStatusCode.NoContent)
            }

            post("/api/admin/projects/{slug}/block") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }
                val body = try {
                    call.receive<StatusChangeRequest>()
                } catch (e: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A reason is required")
                    return@post
                }

                val reason = InputValidators.requireNonBlank(body.reason, "reason")
                if (reason == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A non-empty reason is required")
                    return@post
                }
                if ((body.blockReasonNote?.length ?: reason.length) > 500) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_block_reason_note", "blockReasonNote must be at most 500 characters")
                    return@post
                }
                val blockReasonCode = body.blockReasonCode?.let { code ->
                    AccessBlockReason.entries.firstOrNull { it.value == code }
                        ?: run {
                            call.respondError(HttpStatusCode.BadRequest, "invalid_block_reason_code", "Unsupported blockReasonCode")
                            return@post
                        }
                } ?: AccessBlockReason.MANUAL_HOLD

                val project = projects.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@post
                }

                val principal = call.principal<JWTPrincipal>()
                val actor = principal?.payload?.subject ?: "unknown"

                projects.updateStatus(
                    project.id, "manual_block", actor, reason,
                    blockReason = blockReasonCode.value,
                    blockReasonCode = blockReasonCode,
                    blockReasonNote = body.blockReasonNote?.takeIf { it.isNotBlank() } ?: reason
                )
                projects.invalidateCache(slug)

                projectAdminLogger.info("Project blocked: $slug by $actor")
                call.respond(StatusChangeResponse(status = "blocked", slug = slug))
            }

            post("/api/admin/projects/{slug}/unblock") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@post
                }
                val body = try {
                    call.receive<StatusChangeRequest>()
                } catch (_: Exception) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A reason is required")
                    return@post
                }

                val reason = InputValidators.requireNonBlank(body.reason, "reason")
                if (reason == null) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid_request", "A non-empty reason is required")
                    return@post
                }

                val project = projects.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@post
                }

                val principal = call.principal<JWTPrincipal>()
                val actor = principal?.payload?.subject ?: "unknown"

                projects.updateStatus(project.id, "active", actor, reason)
                projects.invalidateCache(slug)

                projectAdminLogger.info("Project unblocked: $slug by $actor")
                call.respond(StatusChangeResponse(status = "active", slug = slug))
            }


            get("/api/admin/audit") {
                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 500)
                val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val query = call.request.queryParameters["q"]
                val action = call.request.queryParameters["action"]
                val sort = call.request.queryParameters["sort"] ?: "createdAt"
                val direction = if (call.request.queryParameters["direction"]?.lowercase() == "asc") AuditRepositoryPort.SortDirection.ASC else AuditRepositoryPort.SortDirection.DESC
                val total = auditRepository.count(query, action)
                val entries = auditRepository.findPage(limit, offset, query, action, sort, direction).map { it.toResponse() }
                call.respond(AuditLogPageResponse(entries, total, limit, offset, offset + entries.size < total))
            }

            get("/api/admin/projects/{slug}/audit") {
                val slug = call.parameters["slug"]
                if (slug == null) {
                    call.respondError(HttpStatusCode.BadRequest, "missing_slug", "Missing slug path parameter")
                    return@get
                }
                val project = projects.findBySlug(slug)
                if (project == null) {
                    call.respondError(HttpStatusCode.NotFound, "project_not_found", "Project not found")
                    return@get
                }
                val auditLog = auditRepository.findByProjectId(project.id).map { it.toResponse() }
                call.respond(auditLog)
            }


        }
    }
}
