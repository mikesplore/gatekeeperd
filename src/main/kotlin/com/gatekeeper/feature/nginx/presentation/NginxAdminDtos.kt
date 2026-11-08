package com.gatekeeper.feature.nginx.presentation

import com.gatekeeper.db.tables.ReconciliationStatus
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlinx.serialization.SerialName

data class NginxBackfillReport(
    val migrated: List<String>,
    val skippedNoProject: List<String>,
    val failedDiff: Map<String, String>,
    val skippedExistingSite: List<String> = emptyList(),
    val skippedSelfDomain: List<String> = emptyList()
) {
    val migratedCount get() = migrated.size
    val skippedCount get() = skippedNoProject.size + skippedExistingSite.size + skippedSelfDomain.size
    val failedCount get() = failedDiff.size
}

@Serializable
data class NginxEnableResponse(
    val success: Boolean,
    val message: String,
    val config: String? = null,
    val appPort: Int? = null,
    val sslEnabled: Boolean? = null,
    val certificateDomain: String? = null,
    val deploymentConnected: Boolean? = null
)

@Serializable
data class NginxDisableResponse(val success: Boolean, val message: String)

@Serializable
data class NginxWizardContextResponse(
    val serviceId: String,
    val domainBindings: List<NginxWizardDomainBindingResponse>,
    val installedCertificates: List<String>,
    val runtimeHealth: String? = null
)


@Serializable
data class NginxWizardDomainBindingResponse(
    val id: String,
    val serviceId: String,
    val containerPort: Int?,
    val domain: String,
    val status: String,
    val resolvedHostPort: Int? = null,
    val nginxEnabled: Boolean = false
)


@Serializable
data class UpstreamMigrationRow(
    val siteId: String,
    val projectSlug: String?,
    val domain: String,
    val currentKind: String?,
    val result: String,
    val reason: String? = null
)

@Serializable
data class UpstreamMigrationRollbackRequest(val backupName: String)


data class NginxReconciliationResult(
    val slug: String,
    val status: ReconciliationStatus,
    val nginxError: String? = null,
    val dockerError: String? = null,
    val projectId: UUID? = null
)

data class NginxReconciliationReport(
    val results: List<NginxReconciliationResult>,
    val orphanedFiles: List<String>
)

@Serializable
data class NginxEnableRequest(
    val serviceId: String,
    val domain: String,
    val domainBindingId: String,
    val requireSsl: Boolean = true
)

@Serializable
data class NginxStatusResponse(
    val enabled: Boolean,
    val configPath: String? = null,
    val enabledPath: String? = null,
    val port: Int? = null,
    val sslEnabled: Boolean,
    val certificateDomain: String? = null,
    val domain: String? = null,
    val certificateExpiresAt: String? = null,
    val certificateDaysRemaining: Long? = null
)

@Serializable
data class NginxConfigInspection(
    val slug: String,
    val configPath: String,
    val enabledPath: String,
    val available: Boolean,
    val enabled: Boolean,
    val isSymlink: Boolean,
    val content: String? = null,
    val blocks: List<NginxConfigBlock> = emptyList(),
    val customLocations: List<NginxCustomLocation> = emptyList(),
    val modifiedAt: String? = null,
    val sizeBytes: Long? = null
    ,val managed: Boolean = false,
    val manual: Boolean = false,
    val drifted: Boolean = false,
    val actualSha256: String? = null,
    val managedSha256: String? = null
)

fun NginxConfigInspection.canDeploymentCutoverUpdateRoute(): Boolean =
    available && enabled && managed

@Serializable
enum class NginxConfigClassification {
    @SerialName("self") SELF,
    @SerialName("gatekeeper_managed") GATEKEEPER_MANAGED,
    @SerialName("manual") MANUAL
}

@Serializable
data class ClassifiedNginxConfig(
    val filename: String,
    val serverNames: List<String>,
    val listenPorts: List<Int>,
    val classification: NginxConfigClassification,
    val projectId: String? = null,
    val serviceId: String? = null,
    val siteId: String? = null
)

@Serializable
data class NginxDomainConflict(
    val filename: String?,
    val classification: NginxConfigClassification,
    val domains: List<String>,
    val listenPorts: List<Int>,
    val matchingDomains: List<String>,
    val matchingPorts: List<Int>,
    val linkedToRequestingSite: Boolean
)

@Serializable
data class NginxConfigArtifact(
    val filename: String,
    val domains: List<String> = emptyList(),
    val listenPorts: List<Int> = emptyList(),
    val classification: NginxConfigClassification = NginxConfigClassification.MANUAL,
    val available: Boolean,
    val enabled: Boolean,
    val managed: Boolean,
    val tracked: Boolean,
    val orphaned: Boolean,
    val projectId: String? = null,
    val serviceId: String? = null,
    val siteId: String? = null
)

@Serializable
data class NginxManualConfigDetail(
    val filename: String,
    val domains: List<String>,
    val listenPorts: List<Int>,
    val available: Boolean,
    val enabled: Boolean,
    val content: String
)

data class NginxManualConfigDisableResult(
    val backup: String,
    val matchingManagedConfigs: List<String>
)

@Serializable
data class NginxConfigBlock(
    val type: String,
    val header: String,
    val content: String
)

@Serializable
data class NginxCustomLocation(val id: String, val path: String, val directives: String)

@Serializable
data class NginxCustomLocationRequest(val path: String, val directives: String)

@Serializable
data class NginxCustomLocationResponse(val success: Boolean, val message: String, val locations: List<NginxCustomLocation>)

@Serializable
data class NginxTestResult(
    val valid: Boolean,
    val exitCode: Int,
    val output: String,
    val checkedAt: String
)

@Serializable data class NginxBlockUpdateRequest(val blockIndex: Int, val content: String)
@Serializable data class NginxBlockUpdateResponse(val success: Boolean, val message: String, val config: String, val blockIndex: Int, val validation: NginxTestResult, val reloaded: Boolean = false)
@Serializable data class NginxBackup(val name: String, val createdAt: String, val sizeBytes: Long)
@Serializable data class NginxRollbackResponse(val success: Boolean, val message: String, val validation: NginxTestResult, val reloaded: Boolean = false)

@Serializable
data class CertificateInstallRequest(
    val domain: String,
    val email: String
)

@Serializable
data class CertificateResponse(
    val domain: String,
    val installed: Boolean,
    val certificatePath: String? = null,
    val privateKeyPath: String? = null
)

@Serializable
data class CertificateRenewalResponse(
    val domain: String,
    val renewed: Boolean,
    val certificateExpiresAt: String? = null,
    val certificateDaysRemaining: Long? = null,
    val message: String
)

@Serializable
data class InstalledCertificateInfo(
    val certificateDomain: String,
    val certificatePath: String,
    val privateKeyPath: String,
    val certificateExpiresAt: String? = null,
    val certificateDaysRemaining: Long? = null,
    val renewalStatus: String = "unknown"
)

@Serializable
data class CertificateListResponse(
    val certificates: List<InstalledCertificateInfo>,
    val managerReady: Boolean = true,
    val managerMessage: String? = null
)
data class BackfillProject(val id: UUID, val slug: String, val containerName: String?)
data class BackfillDockerTarget(val containerName: String, val hostPort: Int)
