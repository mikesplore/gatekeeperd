package com.gatekeeper.admin.operations


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.api.respondError
import com.gatekeeper.feature.payment.data.persistence.ProjectBalanceAdapter
import com.gatekeeper.feature.payment.domain.usecase.GetPaymentDashboardData
import com.gatekeeper.feature.payment.domain.usecase.ListPaymentsByProject
import com.gatekeeper.plugins.Metrics
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.*
import com.gatekeeper.feature.customer.domain.repository.CustomerRepository as CustomerStore

fun Route.registerCustomerDashboardRoutes(dependencies: OperationsAdminDependencies) {
    val projects = dependencies.projects
    val nginxSettings = dependencies.nginxSettings
    val certificateRepository = dependencies.certificateRepository
    val integrationOutboxRepository = dependencies.integrationOutboxRepository
    val siteRepository = dependencies.siteRepository
    val artifacts = dependencies.artifacts

    post("/api/admin/dashboard/customers") {
        val body = runCatching { call.receive<DashboardCustomerCreateRequest>() }.getOrElse {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Invalid customer body"
            ); return@post
        }
        if (body.firstName.isBlank() || body.lastName.isBlank()) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_customer",
                "Customer first and last names are required"
            ); return@post
        }
        val email = body.contactEmail?.trim()?.takeIf { it.isNotBlank() }?.lowercase()
        if (email != null && !com.gatekeeper.api.InputValidators.isValidEmail(email)) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_customer_email",
                "Enter a valid customer email address"
            ); return@post
        }
        val customers = call.application.get<CustomerStore>()
        if (email != null && customers.existsByContactEmail(email)) {
            call.respondError(
                HttpStatusCode.Conflict,
                "customer_exists",
                "A customer with that email already exists"
            ); return@post
        }
        val customer = try {
            customers.create(
                body.firstName.trim(),
                body.lastName.trim(),
                email,
                body.contactPhone?.trim()?.takeIf { it.isNotBlank() },
                body.billingStatus
            )
        } catch (error: Exception) {
            if (OperationsDashboardService.isUniqueConstraintViolation(error)) {
                call.respondError(
                    HttpStatusCode.Conflict,
                    "customer_exists",
                    "A customer with that email already exists"
                )
                return@post
            }
            throw error
        }
        call.respond(
            HttpStatusCode.Created,
            DashboardCustomerResponse(
                customer.id.toString(),
                customer.firstName,
                customer.lastName,
                customer.contactEmail,
                customer.contactPhone,
                customer.billingStatus,
                0
            )
        )
    }

    put("/api/admin/dashboard/customers/{id}") {
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: run {
            call.respondError(HttpStatusCode.BadRequest, "invalid_customer_id", "Invalid customer ID"); return@put
        }
        val body = runCatching { call.receive<DashboardCustomerUpdateRequest>() }.getOrElse {
            call.respondError(HttpStatusCode.BadRequest, "invalid_request", "Invalid customer body"); return@put
        }
        if (body.firstName.isBlank() || body.lastName.isBlank()) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_customer", "Customer first and last names are required"); return@put
        }
        val email = body.contactEmail?.trim()?.takeIf { it.isNotBlank() }?.lowercase()
        if (email != null && !com.gatekeeper.api.InputValidators.isValidEmail(email)) {
            call.respondError(HttpStatusCode.BadRequest, "invalid_customer_email", "Enter a valid customer email address"); return@put
        }
        val customers = call.application.get<CustomerStore>()
        val current = customers.findById(id) ?: run {
            call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found"); return@put
        }
        if (email != null && !email.equals(current.contactEmail, ignoreCase = true) && customers.existsByContactEmail(email)) {
            call.respondError(HttpStatusCode.Conflict, "customer_exists", "A customer with that email already exists"); return@put
        }
        val customer = customers.update(id, body.firstName.trim(), body.lastName.trim(), email, body.contactPhone?.trim()?.takeIf { it.isNotBlank() }) ?: run {
            call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found"); return@put
        }
        call.respond(DashboardCustomerResponse(customer.id.toString(), customer.firstName, customer.lastName, customer.contactEmail, customer.contactPhone, customer.billingStatus, projects.findByCustomerId(id).size))
    }

    delete("/api/admin/dashboard/customers/{id}") {
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: run {
            call.respondError(HttpStatusCode.BadRequest, "invalid_customer_id", "Invalid customer ID"); return@delete
        }
        val customers = call.application.get<CustomerStore>()
        if (customers.findById(id) == null) {
            call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found"); return@delete
        }
        if (projects.findByCustomerId(id).isNotEmpty()) {
            call.respondError(HttpStatusCode.Conflict, "customer_has_projects", "Reassign this customer's projects before deleting the customer"); return@delete
        }
        try {
            customers.delete(id)
        } catch (_: Exception) {
            call.respondError(HttpStatusCode.Conflict, "customer_has_projects", "Reassign this customer's projects before deleting the customer"); return@delete
        }
        call.respond(mapOf("deleted" to true, "id" to id.toString()))
    }

    patch("/api/admin/dashboard/projects/{id}") {
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: run {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_project_id",
                "Invalid project ID"
            ); return@patch
        }
        val body = runCatching { call.receive<DashboardProjectCustomerUpdateRequest>() }.getOrElse {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Invalid project body"
            ); return@patch
        }
        val customerId = runCatching { UUID.fromString(body.customerId) }.getOrNull()
        if (customerId == null) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_customer_id",
                "Invalid customer ID"
            ); return@patch
        }
        if (call.application.get<CustomerStore>().findById(customerId) == null) {
            call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found"); return@patch
        }
        val project = projects.assignCustomer(id, customerId) ?: run {
            call.respondError(
                HttpStatusCode.NotFound,
                "project_not_found",
                "Project not found"
            ); return@patch
        }
        call.respond(project)
    }

    get("/api/admin/dashboard/customers") {
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        val query = call.request.queryParameters["q"]
        val customerRepository = call.application.get<CustomerStore>()
        val (customers, total) = customerRepository.findPage(query, limit, offset)
        val responses = customers.map { customer ->
            val owned = OperationsDashboardService.customerOwnedProjects(projects, siteRepository,customer.id)
            val balances = call.application.get<ProjectBalanceAdapter>()
            val financials = owned.map { OperationsDashboardService.dashboardProjectFinancials(it.project, balances) }
            val billed = financials.sumOf { it.billed }
            val paid = financials.sumOf { it.paid }
            val balance = financials.sumOf { it.balance }
            DashboardCustomerResponse(
                customer.id.toString(),
                customer.firstName,
                customer.lastName,
                customer.contactEmail,
                customer.contactPhone,
                OperationsDashboardService.derivedBillingStatus(financials),
                owned.size,
                owned.size,
                emptyMap(),
                billed.toDouble(),
                paid.toDouble(),
                balance.toDouble()
            )
        }
        call.respond(
            DashboardCustomersPageResponse(
                responses,
                total,
                limit,
                offset,
                offset + responses.size < total
            )
        )
    }

    get("/api/admin/dashboard/customers/{id}") {
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: run {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_customer_id",
                "Invalid customer ID"
            ); return@get
        }
        val customer = call.application.get<CustomerStore>().findById(id) ?: run {
            call.respondError(
                HttpStatusCode.NotFound,
                "customer_not_found",
                "Customer not found"
            ); return@get
        }
        val owned = OperationsDashboardService.customerOwnedProjects(projects, siteRepository,id)
        val balances = call.application.get<ProjectBalanceAdapter>()
        val financials = owned.map { OperationsDashboardService.dashboardProjectFinancials(it.project, balances) }
        val projects = owned.map { ownedProject ->
            val project = ownedProject.project
            val projectFinancials = OperationsDashboardService.dashboardProjectFinancials(project, balances)
            DashboardCustomerProjectResponse(
                id = project.id.toString(), slug = project.slug, name = project.name, domain = project.domain,
                amountDue = projectFinancials.billed.toDouble(), totalPaid = projectFinancials.paid.toDouble(),
                balance = projectFinancials.balance.toDouble(),
                status = project.status
            )
        }
        call.respond(
            DashboardCustomerResponse(
                customer.id.toString(),
                customer.firstName,
                customer.lastName,
                customer.contactEmail,
                customer.contactPhone,
                OperationsDashboardService.derivedBillingStatus(financials),
                projects.size,
                projects.size,
                emptyMap(),
                totalBilled = projects.sumOf { it.amountDue ?: 0.0 },
                totalPaid = projects.sumOf { it.totalPaid },
                balance = projects.sumOf { it.balance },
                projects = projects
            )
        )
    }

    get("/api/admin/dashboard/customers/{id}/transactions") {
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: run {
            call.respondError(
                HttpStatusCode.BadRequest,
                "invalid_customer_id",
                "Invalid customer ID"
            ); return@get
        }
        if (call.application.get<CustomerStore>().findById(id) == null) {
            call.respondError(HttpStatusCode.NotFound, "customer_not_found", "Customer not found"); return@get
        }
        val listPayments = call.application.get<ListPaymentsByProject>()
        val transactions = OperationsDashboardService.customerOwnedProjects(projects, siteRepository,id).flatMap { owned ->
            listPayments(owned.project.id).map { payment ->
                DashboardCustomerTransactionResponse(
                    payment.id.toString(),
                    owned.project.id.toString(),
                    owned.project.name,
                    owned.project.slug,
                    payment.amount.toDouble(),
                    payment.recordStatus,
                    payment.status,
                    payment.provider,
                    payment.providerReference,
                    payment.paidAt?.toString(),
                    payment.createdAt?.toString().orEmpty()
                )
            }
        }.sortedByDescending { it.createdAt }
        call.respond(transactions)
    }

    get("/api/admin/dashboard/summary") {
        val allProjects = projects.findAll()
        val paymentDashboard = call.application.get<GetPaymentDashboardData>()()
        val outbox = integrationOutboxRepository.summary()
        val nginxArtifacts = artifacts.execute()
        val available = nginxArtifacts.count { it.available }.toLong()
        val enabled = nginxArtifacts.count { it.enabled }.toLong()
        val selfDomains = nginxSettings.selfDomains.toSet()
        val siteCounts =
            siteRepository.findAll().filterNot { it.domain.lowercase().trimEnd('.') in selfDomains }
                .groupingBy { it.reconciliationStatus.value }.eachCount().mapValues { it.value.toLong() }
        val now = LocalDateTime.now()
        val certificateAlerts = certificateRepository.findAll().flatMap { certificate ->
            buildList {
                certificate.lastRenewalError?.let { add("certificate for ${certificate.domain} renewal failed: $it") }
                certificate.expiresAt?.let { expires ->
                    val days = java.time.Duration.between(now, expires).toDays()
                    if (days <= 7) add("certificate for ${certificate.domain} expires in ${days.coerceAtLeast(0)} days")
                }
            }
        }
        call.respond(
            DashboardSummaryResponse(
                OffsetDateTime.now().toString(),
                allProjects.groupingBy { it.status.lowercase() }.eachCount().mapValues { it.value.toLong() },
                paymentDashboard.paymentCountsByStatus,
                mapOf(
                    "thisMonth" to paymentDashboard.thisMonthRevenue,
                    "lastMonth" to paymentDashboard.lastMonthRevenue
                ),
                mapOf(
                    "outboxPending" to outbox.pending,
                    "outboxProcessing" to outbox.processing,
                    "outboxDeadLetter" to outbox.deadLetter,
                    "outboxDelivered" to outbox.delivered
                ),
                siteCounts + mapOf("availableSites" to available, "enabledSites" to enabled),
                Metrics.snapshot(),
                certificateAlerts
            )
        )
    }
}
