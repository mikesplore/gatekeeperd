package com.gatekeeper.admin.model


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.feature.project.domain.model.ProjectRecord
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.*
import com.gatekeeper.feature.service.domain.repository.SiteRecord

// Extracted route DTOs
@Serializable
data class ProjectHealthResponse(
    val project: com.gatekeeper.api.dto.ProjectResponse,
    val runtimeHealth: String? = null,
    val nginxEnabled: Boolean,
    val certificateInstalled: Boolean,
    val readiness: String
)

@Serializable
data class BulkProjectRequest(
    val slugs: List<String>,
    val reason: String,
    val blockReasonCode: String? = null,
    val blockReasonNote: String? = null
)

@Serializable
data class BulkProjectResult(val slug: String, val status: String, val message: String? = null)

@Serializable
data class DashboardSummaryResponse(
    val generatedAt: String,
    val projects: Map<String, Long>,
    val payments: Map<String, Long>,
    val revenue: Map<String, String>,
    val integrations: Map<String, Long>,
    val nginx: Map<String, Long>,
    val metrics: Map<String, Long>,
    val certificateAlerts: List<String> = emptyList()
)

@Serializable
data class DashboardSiteResponse(
    val slug: String, val projectId: String, val customerId: String? = null, val customerName: String? = null,
    val domain: String, val status: String,
    val lastNginxError: String? = null, val lastDockerError: String? = null,
    val configVersion: Int, val available: Boolean, val enabled: Boolean,
    val upstreamHost: String? = null, val upstreamMode: String? = null,
    val upstreamExplicitPort: Int? = null, val runtimeHealth: String? = null,
    val resolvedUpstreamHost: String? = null, val resolvedUpstreamPort: Int? = null,
    val tlsMode: String? = null, val gateEnabled: Boolean? = null, val serviceId: String? = null,
    val upstreamKind: String? = null
)

@Serializable
data class DashboardSiteDetailResponse(
    val site: DashboardSiteResponse, val generatedConfig: String? = null, val currentConfig: String? = null,
    val certificateInstalled: Boolean? = null, val certificateDaysRemaining: Long? = null,
    val backups: List<String> = emptyList()
)

@Serializable
data class DashboardSitesPageResponse(
    val sites: List<DashboardSiteResponse>,
    val total: Long,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean
)

@Serializable
data class DashboardSiteDeleteResponse(val deleted: Boolean, val slug: String)

@Serializable
data class NginxConfigArtifactBackupResponse(val filename: String, val backup: String? = null)

@Serializable
data class NginxManualConfigActionResponse(
    val filename: String,
    val backup: String,
    val disabled: Boolean = false,
    val deleted: Boolean = false,
    val nginxTestPassed: Boolean = true,
    val reloaded: Boolean = false,
    val resolvedManagedConfigs: List<String> = emptyList()
)

@Serializable
data class DashboardCustomerResponse(
    val id: String, val firstName: String, val lastName: String, val contactEmail: String? = null, val contactPhone: String? = null,
    val billingStatus: String, val projectCount: Int, val siteCount: Int = projectCount,
    val health: Map<String, Int> = emptyMap(),
    val totalBilled: Double = 0.0, val totalPaid: Double = 0.0, val balance: Double = 0.0,
    val projects: List<DashboardCustomerProjectResponse> = emptyList()
)

@Serializable
data class DashboardCustomersPageResponse(
    val customers: List<DashboardCustomerResponse>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean
)

@Serializable
data class DashboardCustomerProjectResponse(
    val id: String, val slug: String, val name: String, val domain: String,
    val amountDue: Double? = null, val totalPaid: Double = 0.0, val balance: Double = 0.0,
    val status: String
)

@Serializable
data class DashboardCustomerTransactionResponse(
    val id: String, val projectId: String, val projectName: String, val projectSlug: String,
    val amount: Double, val status: String, val gatewayStatus: String,
    val provider: String, val providerReference: String, val paidAt: String? = null, val createdAt: String
)

@Serializable
data class DashboardSiteUpdateRequest(
    val domain: String? = null, val upstreamHost: String? = null, val upstreamMode: String? = null,
    val upstreamExplicitPort: Int? = null,
    val tlsMode: String? = null, val certMode: String? = null, val certExplicitPath: String? = null,
    val gateEnabled: Boolean? = null, val bypassPaths: List<String>? = null
)

@Serializable
data class DashboardCustomerCreateRequest(
    val firstName: String, val lastName: String, val contactEmail: String? = null, val contactPhone: String? = null,
    val billingStatus: String = "unknown"
)

@Serializable
data class DashboardProjectCustomerUpdateRequest(val customerId: String)

@Serializable
data class DashboardConfirmationRequest(val confirm: Boolean = false)

data class CustomerOwnedProject(
    val project: ProjectRecord,
    val site: SiteRecord?
)

data class DashboardProjectFinancials(
    val billed: java.math.BigDecimal,
    val paid: java.math.BigDecimal,
    val balance: java.math.BigDecimal
)

@Serializable
data class NotificationResponse(
    val id: String,
    val title: String,
    val message: String,
    val severity: String,
    val action: String,
    val createdAt: String,
    val read: Boolean = false
)
