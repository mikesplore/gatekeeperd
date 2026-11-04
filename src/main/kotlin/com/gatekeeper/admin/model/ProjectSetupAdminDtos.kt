package com.gatekeeper.admin.model


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import com.gatekeeper.deployment.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// Extracted route DTOs
@Serializable
data class CreateProjectSetupRequest(
    val name: String,
    val domain: String = "",
    val customerId: String? = null,
    val amountDue: Double? = null,
    val currency: String = "KES",
    val dueDate: String? = null,
    val gracePeriodDays: Int = 3
)

@Serializable
data class ProjectSetupCreatedResponse(val projectId: String, val slug: String, val status: String = "created")

@Serializable
data class ProjectSharedEnvironmentRequest(
    val environment: String = "production",
    val values: Map<String, String>
)

@Serializable
data class ProjectEnvironmentDeployResponse(
    val setId: String,
    val version: Int,
    val deploymentIds: List<String>
)

@Serializable
data class ProjectSetupGatewayRequest(
    val domain: String,
    val serviceId: String? = null,
    val containerPort: Int? = null
)

@Serializable
data class ProjectSetupDomainBinding(
    val id: String,
    val serviceId: String,
    val containerPort: Int?,
    val domain: String,
    val status: String
)

@Serializable
data class ProjectSetupGatewayResponse(
    val domain: String,
    val status: String,
    val serviceId: String? = null,
    val containerPort: Int? = null,
    val bindings: List<ProjectSetupDomainBinding> = emptyList()
)

@Serializable
data class ProjectSetupConfigurationResponse(
    val id: String,
    val repository: String?,
    val gitRef: String,
    val registry: String,
    val imageName: String,
    val imageTag: String,
    val autoDeploy: Boolean,
    val containerPort: Int?,
    val hostPort: Int?,
    val network: String,
    val restartPolicy: String,
    val environment: String,
    val env: Map<String, String>,
    val envKeys: List<String>,
    val secretSetId: String?,
    val secretSetVersion: Int?
)

@Serializable
data class ProjectSetupStatusResponse(
    val projectId: String,
    val slug: String,
    val name: String,
    val domain: String,
    val sourceRuntime: ProjectSetupConfigurationResponse? = null,
    val gateway: ProjectSetupGatewayResponse? = null,
    val activeDeploymentId: String? = null,
    val activeDeploymentStatus: String? = null,
    val latestDeploymentId: String? = null,
    val latestDeploymentStatus: String? = null,
    val serviceId: String? = null
)

@Serializable
data class ProjectSetupSourceRuntimeResponse(val configurationId: String, val environment: String, val status: String = "configured")

@Serializable
data class ProjectSetupDeployResponse(val deploymentId: String, val status: String = "queued")

@Serializable
data class ProjectSetupCancelResponse(val deploymentId: String, val status: String = "cancelled")

@Serializable
data class ProjectOverviewAccessLifecycle(
    val accessStatus: String,
    val blockReason: String?,
    val deploymentMode: String,
    val serviceMode: String,
    val lifecycleStatus: String
)

@Serializable
data class ProjectOverviewDesiredConfiguration(
    val configurationId: String?,
    val environment: String?,
    val repository: String?,
    val gitRef: String?,
    val registry: String?,
    val imageName: String?,
    val imageTag: String?,
    val containerPort: Int?,
    val envKeys: List<String>,
    val secretSetId: String?,
    val secretSetVersion: Int?
)

@Serializable
data class ProjectOverviewPortMapping(
    val containerPort: Int,
    val hostPort: Int,
    val hostIp: String?
)

@Serializable
data class ProjectOverviewCurrentDeployment(
    val id: String?,
    val status: String,
    val environment: String,
    val triggerSource: String?,
    val createdAt: String?,
    val activeAt: String?,
    val imageName: String?,
    val imageTag: String?,
    val imageDigest: String?,
    val commitSha: String?,
    val runtimeHealth: String,
    val runtimeUpstreamHost: String?,
    val runtimeUpstreamPort: Int?,
    val publicExposure: Boolean,
    val credentialSetId: String?,
    val credentialSetVersion: Int?,
    val secretSetId: String?,
    val secretSetVersion: Int?,
    val portMappings: List<ProjectOverviewPortMapping> = emptyList()
)

@Serializable
data class ProjectOverviewGateway(
    val siteId: String?,
    val domain: String?,
    val configured: Boolean,
    val tlsMode: String?,
    val gateEnabled: Boolean?,
    val reconciliationStatus: String?,
    val resolvedUpstreamHost: String?,
    val resolvedUpstreamPort: Int?,
    val bindings: List<ProjectSetupDomainBinding> = emptyList(),
    val unboundPorts: List<Int> = emptyList()
)

@Serializable
data class ProjectOverviewCustomerBilling(
    val customerId: String?,
    val customerName: String?,
    val customerEmail: String?,
    val billingName: String?,
    val billingEmail: String?,
    val billingAddress: String?,
    val currency: String,
    val billed: Double,
    val paid: Double,
    val balance: Double,
    val dueDate: String?
)

@Serializable
data class ProjectOverviewResponse(
    val projectId: String,
    val slug: String,
    val name: String,
    val accessLifecycle: ProjectOverviewAccessLifecycle,
    val desiredConfiguration: ProjectOverviewDesiredConfiguration,
    val currentDeployment: ProjectOverviewCurrentDeployment,
    val domainsGateway: ProjectOverviewGateway,
    val customerBilling: ProjectOverviewCustomerBilling
)

@Serializable
data class ProjectDeploymentHistoryItem(
    val id: String,
    val environment: String,
    val sourceCommit: String?,
    val imageName: String,
    val imageTag: String,
    val imageDigest: String?,
    val trigger: String,
    val actor: String?,
    val status: String,
    val createdAt: String,
    val activeAt: String?,
    val healthCheckResult: String,
    val failureReason: String?,
    val credentialSetId: String?,
    val credentialSetVersion: Int?,
    val secretSetId: String?,
    val secretSetVersion: Int?,
    val configurationId: String,
    val actions: List<String>,
    val cutoverState: String,
    val cutoverError: String?,
    val runtimeContainerName: String?,
    val runtimeHostPort: Int?,
    val runtimePorts: Map<String, Int>,
    val runtimePresent: Boolean? = null,
    val runtimeState: String? = null,
    val restartCount: Int? = null
)

@Serializable
data class ProjectDeploymentDiagnosticsResponse(
    val deploymentId: String,
    val status: String,
    val step: String,
    val errorMessage: String?,
    val logs: String
)

@Serializable
data class ProjectDeploymentHistoryResponse(val projectId: String, val environment: String, val items: List<ProjectDeploymentHistoryItem>)

@Serializable
data class AdminDeploymentHistoryItem(
    val id: String,
    val projectId: String,
    val projectSlug: String,
    val environment: String,
    val sourceCommit: String?,
    val imageName: String,
    val imageTag: String,
    val imageDigest: String?,
    val trigger: String,
    val status: String,
    val createdAt: String,
    val activeAt: String?,
    val healthCheckResult: String,
    val failureReason: String?,
    val credentialSetId: String?,
    val credentialSetVersion: Int?,
    val secretSetId: String?,
    val secretSetVersion: Int?,
    val canRedeploy: Boolean,
    val canCancel: Boolean,
    val cutoverState: String,
    val cutoverError: String?,
    val runtimeContainerName: String?,
    val runtimeHostPort: Int?,
    val runtimePorts: Map<String, Int>,
    val runtimePresent: Boolean? = null,
    val runtimeState: String? = null,
    val restartCount: Int? = null
)

@Serializable
data class AdminDeploymentHistoryResponse(
    val items: List<AdminDeploymentHistoryItem>,
    val total: Long,
    val limit: Int,
    val offset: Int
)

@Serializable
data class ProjectSecretRotationRequest(val secretEnv: Map<String, String>)

@Serializable
data class AdoptableContainerPort(val containerPort: Int, val hostPort: Int)

@Serializable
data class AdoptableContainerView(
    val id: String, val name: String, val image: String, val state: String,
    val networks: List<String>, val ports: List<AdoptableContainerPort>, val environmentVariableCount: Int
)

@Serializable
data class AdoptContainerRequest(val containerId: String, val containerPort: Int, val serviceId: String? = null)

@Serializable
data class AdoptContainerResponse(
    val deploymentId: String, val containerName: String, val status: String = "active",
    val environmentVariableCount: Int, val message: String
)
