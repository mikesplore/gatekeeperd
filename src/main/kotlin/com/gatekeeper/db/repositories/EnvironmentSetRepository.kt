package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.DeploymentConfigurations
import com.gatekeeper.db.tables.DeploymentExecutions
import com.gatekeeper.db.tables.DeploymentStatus
import com.gatekeeper.db.tables.Deployments
import com.gatekeeper.db.tables.ProjectSecretSetVersions
import com.gatekeeper.db.tables.ProjectSharedEnvironmentVersions
import com.gatekeeper.db.tables.Services
import com.gatekeeper.security.SecretValueCipher
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

/** Environment metadata readers. Values are returned only for the authenticated service editor response. */
object EnvironmentSetRepository {
    data class VersionMetadata(
        val id: UUID,
        val version: Int,
        val environment: String,
        val keys: List<String>,
        val createdAt: LocalDateTime,
        val createdBy: String?
    )

    data class SharedMetadata(val versions: List<VersionMetadata>) {
        val latest: VersionMetadata? get() = versions.maxByOrNull { it.version }
    }

    data class ServiceMetadata(val effectiveValues: Map<String, String>)

    data class FingerprintedVariable(val key: String, val source: String, val fingerprint: String)

    data class ActiveDeploymentInspection(
        val deploymentId: UUID,
        val status: String,
        val containerName: String?,
        val serviceId: UUID,
        val environment: String,
        val imageName: String,
        val imageTag: String,
        val imageDigest: String?,
        val commitSha: String?,
        val activeAt: LocalDateTime?,
        val sharedSetId: UUID?,
        val sharedSetVersion: Int?,
        val serviceSetId: UUID?,
        val serviceSetVersion: Int?,
        val variables: List<FingerprintedVariable>
    )

    fun sharedMetadata(projectId: UUID, environment: String): SharedMetadata = transaction {
        val versions = ProjectSharedEnvironmentVersions.selectAll().where {
            (ProjectSharedEnvironmentVersions.projectId eq projectId) and
                (ProjectSharedEnvironmentVersions.environment eq environment)
        }.map { row ->
            metadata(
                row[ProjectSharedEnvironmentVersions.id], row[ProjectSharedEnvironmentVersions.version],
                row[ProjectSharedEnvironmentVersions.environment], row[ProjectSharedEnvironmentVersions.encryptedPayload],
                row[ProjectSharedEnvironmentVersions.createdAt], row[ProjectSharedEnvironmentVersions.createdBy]
            )
        }.sortedByDescending { it.version }
        SharedMetadata(versions)
    }

    fun serviceMetadata(projectId: UUID, serviceId: UUID, environment: String): ServiceMetadata? = transaction {
        val service = Services.selectAll().where {
            (Services.projectId eq projectId) and (Services.id eq serviceId)
        }.singleOrNull() ?: return@transaction null
        val versionRows = ProjectSecretSetVersions.selectAll().where {
            (ProjectSecretSetVersions.projectId eq projectId) and
                (ProjectSecretSetVersions.environment eq environment)
        }.toList().filter {
            it[ProjectSecretSetVersions.serviceId] == serviceId ||
                (service[Services.isDefault] && it[ProjectSecretSetVersions.serviceId] == null)
        }
        val configurations = DeploymentConfigurations.selectAll().where {
            (DeploymentConfigurations.projectId eq projectId) and
                ((DeploymentConfigurations.serviceId eq serviceId) or DeploymentConfigurations.serviceId.isNull())
        }.toList().filter { it[DeploymentConfigurations.environment] == environment }
            .sortedByDescending { it[DeploymentConfigurations.serviceId] == serviceId }
        val configured = configurations.firstOrNull()
        val configuredServiceValues = when {
            configured == null -> versionRows.maxByOrNull { it[ProjectSecretSetVersions.version] }?.let { row ->
                Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(row[ProjectSecretSetVersions.encryptedPayload]))
            }.orEmpty()
            configured[DeploymentConfigurations.secretSetId] != null -> readServiceValues(
                configured[DeploymentConfigurations.secretSetId], configured[DeploymentConfigurations.secretSetVersion],
                projectId, configured[DeploymentConfigurations.serviceId], environment
            )
            else -> {
                val legacySecrets = configured[DeploymentConfigurations.secretEnvEncrypted]?.let {
                    Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(it))
                }.orEmpty()
                Json.decodeFromString<Map<String, String>>(configured[DeploymentConfigurations.envJson]) + legacySecrets
            }
        }
        val configuredSharedValues = configured?.let {
            readSharedValues(
                it[DeploymentConfigurations.sharedEnvironmentSetId], it[DeploymentConfigurations.sharedEnvironmentSetVersion],
                projectId, environment
            )
        }.orEmpty()
        ServiceMetadata(configuredSharedValues + configuredServiceValues)
    }

    fun activeDeployment(serviceId: UUID, environment: String): ActiveDeploymentInspection? =
        deploymentInspection(serviceId, environment, includeReadyCandidate = false)

    fun runtimeDeployment(serviceId: UUID, environment: String): ActiveDeploymentInspection? =
        deploymentInspection(serviceId, environment, includeReadyCandidate = true)

    private fun deploymentInspection(
        serviceId: UUID,
        environment: String,
        includeReadyCandidate: Boolean
    ): ActiveDeploymentInspection? = transaction {
        val active = Deployments.selectAll().where {
            (Deployments.serviceId eq serviceId) and (Deployments.environment eq environment) and
                (Deployments.status eq DeploymentStatus.ACTIVE)
        }.singleOrNull()
        val deployment = active ?: if (includeReadyCandidate) {
            Deployments.selectAll().where {
                (Deployments.serviceId eq serviceId) and (Deployments.environment eq environment) and
                    (Deployments.status eq DeploymentStatus.READY)
            }.maxByOrNull { it[Deployments.createdAt] }
        } else null
        deployment ?: return@transaction null
        val execution = DeploymentExecutions.selectAll().where {
            DeploymentExecutions.id eq deployment[Deployments.executionId]
        }.singleOrNull() ?: return@transaction null

        val sharedSetId = execution[DeploymentExecutions.sharedEnvironmentSetId]
        val sharedVersion = execution[DeploymentExecutions.sharedEnvironmentSetVersion]
        val serviceSetId = deployment[Deployments.secretSetId]
            ?: execution[DeploymentExecutions.secretSetId]
        val serviceVersion = deployment[Deployments.secretSetVersion]
            ?: execution[DeploymentExecutions.secretSetVersion]
        val projectId = execution[DeploymentExecutions.projectId]
        val executionServiceId = execution[DeploymentExecutions.serviceId]
        check(executionServiceId == null || executionServiceId == serviceId) { "Deployment service does not match the requested service" }
        val snapshotEnvironment = execution[DeploymentExecutions.environment]
        val sharedValues = readSharedValues(sharedSetId, sharedVersion, projectId, snapshotEnvironment)
        val serviceValues = readServiceValues(serviceSetId, serviceVersion, projectId, executionServiceId, snapshotEnvironment)
        val legacyValues = if (serviceSetId == null) {
            Json.decodeFromString<Map<String, String>>(execution[DeploymentExecutions.envJson]) +
                (execution[DeploymentExecutions.secretEnvEncrypted]?.let {
                    Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(it))
                } ?: emptyMap())
        } else emptyMap()
        val effectiveServiceValues = serviceValues + legacyValues
        val values = sharedValues + effectiveServiceValues
        val storedSources = runCatching {
            Json.decodeFromString<Map<String, String>>(execution[DeploymentExecutions.resolvedEnvironmentSourcesJson])
        }.getOrDefault(emptyMap())
        val variables = values.toSortedMap().map { (key, value) ->
            val source = storedSources[key] ?: if (key in effectiveServiceValues) "service" else "project_shared"
            FingerprintedVariable(key, source, SecretValueCipher.fingerprint("$key\u0000$value"))
        }
        ActiveDeploymentInspection(
            deployment[Deployments.id], deployment[Deployments.status].value, deployment[Deployments.runtimeContainerName], serviceId, environment,
            execution[DeploymentExecutions.imageName], execution[DeploymentExecutions.imageTag],
            execution[DeploymentExecutions.imageDigest], execution[DeploymentExecutions.commitSha],
            deployment[Deployments.activeAt], sharedSetId, sharedVersion, serviceSetId, serviceVersion, variables
        )
    }

    private fun metadata(
        id: UUID, version: Int, environment: String, encryptedPayload: String,
        createdAt: LocalDateTime, createdBy: String?
    ) = VersionMetadata(
        id, version, environment,
        Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(encryptedPayload)).keys.sorted(),
        createdAt, createdBy
    )

    private fun readSharedValues(id: UUID?, version: Int?, projectId: UUID, environment: String): Map<String, String> {
        if (id == null && version == null) return emptyMap()
        require(id != null && version != null) { "Shared environment reference is incomplete" }
        val row = ProjectSharedEnvironmentVersions.selectAll().where {
            (ProjectSharedEnvironmentVersions.id eq id) and (ProjectSharedEnvironmentVersions.version eq version)
        }.singleOrNull() ?: error("Shared environment version not found")
        check(row[ProjectSharedEnvironmentVersions.projectId] == projectId) { "Shared environment project does not match deployment" }
        check(row[ProjectSharedEnvironmentVersions.environment] == environment) { "Shared environment scope does not match deployment" }
        return Json.decodeFromString(SecretValueCipher.decrypt(row[ProjectSharedEnvironmentVersions.encryptedPayload]))
    }

    private fun readServiceValues(id: UUID?, version: Int?, projectId: UUID, serviceId: UUID?, environment: String): Map<String, String> {
        if (id == null && version == null) return emptyMap()
        require(id != null && version != null) { "Service environment reference is incomplete" }
        val row = ProjectSecretSetVersions.selectAll().where {
            (ProjectSecretSetVersions.id eq id) and (ProjectSecretSetVersions.version eq version)
        }.singleOrNull() ?: error("Service environment version not found")
        check(row[ProjectSecretSetVersions.projectId] == projectId) { "Service environment project does not match deployment" }
        check(serviceId == null || row[ProjectSecretSetVersions.serviceId] == serviceId) { "Service environment owner does not match deployment" }
        check(row[ProjectSecretSetVersions.environment] == environment) { "Service environment scope does not match deployment" }
        return Json.decodeFromString(SecretValueCipher.decrypt(row[ProjectSecretSetVersions.encryptedPayload]))
    }
}
