package com.gatekeeper.admin.model


import com.gatekeeper.admin.model.*
import com.gatekeeper.admin.application.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Extracted route DTOs
@Serializable
data class ServiceAdminView(
    val id: String, val projectId: String, val name: String, val serviceType: String, val accessStatus: String,
    val isDefault: Boolean,
    val blockReason: String?, val blockReasonCode: String? = null, val blockReasonNote: String? = null
)

@Serializable
data class CreateServiceRequest(val name: String, val serviceType: String = "frontend")

@Serializable
data class ServiceDeletionResponse(
    val deleted: Boolean,
    val serviceId: String,
    val domainsReleased: List<String>,
    val containersRemoved: List<String>,
    val retained: List<String>
)

@Serializable
data class CreateServiceAdjustmentRequest(val type: String, val amount: Double, val reason: String)

@Serializable
data class CreateServiceInvoiceRequest(val description: String, val amount: Double)

@Serializable
data class ServiceInvoiceResponse(val status: String, val serviceId: String, val invoice: JsonObject)

@Serializable
data class ServiceAdjustmentView(val id: String, val projectId: String, val serviceId: String, val type: String, val amount: Double, val reason: String, val actor: String, val createdAt: String)

@Serializable
data class UpdateServiceRequest(
    val name: String? = null, val accessStatus: String? = null, val blockReason: String? = null,
    val blockReasonCode: String? = null, val blockReasonNote: String? = null,
    val isDefault: Boolean? = null, val serviceType: String? = null
)

@Serializable
data class ServiceDomainBindingRequest(val domain: String, val containerPort: Int? = null)

@Serializable
data class ServiceDomainBindingView(
    val id: String, val serviceId: String, val domain: String,
    val containerPort: Int?, val status: String
)

@Serializable
data class ServiceDomainBindingsResponse(
    val serviceId: String,
    val publishedPorts: List<Int>,
    val bindings: List<ServiceDomainBindingView>,
    val unboundPorts: List<Int>
)

@Serializable
data class EnvironmentVersionView(
    val id: String, val version: Int, val environment: String, val keys: List<String>, val createdAt: String, val createdBy: String?
)

@Serializable
data class SharedEnvironmentMetadataView(
    val projectId: String, val environment: String, val latest: EnvironmentVersionView?,
    val versions: List<EnvironmentVersionView>, val values: String = "write-only"
)

@Serializable
data class ServiceEnvironmentMetadataView(
    val projectId: String, val serviceId: String, val environment: String,
    val effectiveValues: Map<String, String>
)

@Serializable
data class EnvironmentWriteRequest(
    val environment: String = "production",
    val values: Map<String, String>,
    val replaceExisting: Boolean = false,
    val sharedEnvironmentSetId: String? = null,
    val sharedEnvironmentSetVersion: Int? = null
)

@Serializable
data class EnvironmentWriteResponse(
    val deploymentIds: List<String>
)

@Serializable
data class FingerprintedEnvironmentVariableView(val key: String, val source: String, val fingerprint: String)

@Serializable
data class ActiveDeploymentInspectionView(
    val deploymentId: String, val status: String, val containerName: String?, val serviceId: String, val environment: String,
    val publishedPorts: Map<String, Int> = emptyMap(),
    val imageName: String, val imageTag: String, val imageDigest: String?, val commitSha: String?, val activeAt: String?,
    val sharedSetId: String?, val sharedSetVersion: Int?, val serviceSetId: String?, val serviceSetVersion: Int?,
    val variables: List<FingerprintedEnvironmentVariableView>,
    val publicExposure: Boolean = false,
    val fingerprintAlgorithm: String = "HMAC-SHA-256"
)
