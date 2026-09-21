package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.DeploymentJobs
import com.gatekeeper.db.tables.DeploymentConfigurations
import com.gatekeeper.db.tables.DeploymentExecutions
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID
import com.gatekeeper.docker.VolumeMount
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.gatekeeper.security.SecretValueCipher
import com.gatekeeper.deployment.UpdateDeploymentConfigurationRequest

data class DeploymentJobRecord(
    val id: UUID, val repository: String, val gitRef: String, val registry: String,
    val imageName: String, val imageTag: String, val containerName: String?, val hostPort: Int?, val containerPort: Int?, val network: String, val restartPolicy: String, val env: Map<String, String>, val secretEnv: Map<String, String>, val volumes: List<VolumeMount>, val createNetworkIfMissing: Boolean,
    val status: String, val currentStep: String,
    val logs: String, val commitSha: String?, val imageDigest: String?, val errorMessage: String?,
    val createdAt: LocalDateTime, val startedAt: LocalDateTime?, val completedAt: LocalDateTime?, val updatedAt: LocalDateTime,
    val previousContainerName: String?, val previousImage: String?, val projectSlug: String?, val triggerSource: String
)

object DeploymentJobRepository {
    fun updateConfiguration(id: UUID, request: UpdateDeploymentConfigurationRequest): Boolean = transaction {
        val row = DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq id }.singleOrNull() ?: return@transaction false
        val newSecrets = request.secretEnv?.let { values ->
            if (values.isEmpty()) null else {
                check(SecretValueCipher.isConfigured()) { "Deployment secret encryption is not configured" }
                SecretValueCipher.encrypt(Json.encodeToString(values))
            }
        }
        val envJson = request.env?.let(Json::encodeToString)
        val volumesJson = request.volumes?.let(Json::encodeToString)
        DeploymentConfigurations.update({ DeploymentConfigurations.id eq id }) {
            request.repository?.let { value -> it[repository] = value }; request.gitRef?.let { value -> it[gitRef] = value }
            request.registry?.let { value -> it[registry] = value }; request.imageName?.let { value -> it[imageName] = value }
            request.imageTag?.let { value -> it[imageTag] = value }; request.containerName?.let { value -> it[containerName] = value }
            request.hostPort?.let { value -> it[hostPort] = value }; request.containerPort?.let { value -> it[containerPort] = value }
            request.network?.let { value -> it[network] = value }; request.restartPolicy?.let { value -> it[restartPolicy] = value }
            envJson?.let { value -> it[DeploymentConfigurations.envJson] = value }; volumesJson?.let { value -> it[DeploymentConfigurations.volumesJson] = value }
            request.createNetworkIfMissing?.let { value -> it[createNetworkIfMissing] = value }
            if (request.secretEnv != null) it[secretEnvEncrypted] = newSecrets
            it[updatedAt] = LocalDateTime.now()
        }
        // Keep the compatibility row aligned until workers are fully moved to executions.
        DeploymentJobs.update({ DeploymentJobs.id eq id }) {
            request.repository?.let { value -> it[repository] = value }; request.gitRef?.let { value -> it[gitRef] = value }
            request.registry?.let { value -> it[registry] = value }; request.imageName?.let { value -> it[imageName] = value }
            request.imageTag?.let { value -> it[imageTag] = value }; request.containerName?.let { value -> it[containerName] = value }
            request.hostPort?.let { value -> it[hostPort] = value }; request.containerPort?.let { value -> it[containerPort] = value }
            request.network?.let { value -> it[network] = value }; request.restartPolicy?.let { value -> it[restartPolicy] = value }
            envJson?.let { value -> it[DeploymentJobs.envJson] = value }; volumesJson?.let { value -> it[DeploymentJobs.volumesJson] = value }
            request.createNetworkIfMissing?.let { value -> it[createNetworkIfMissing] = value }
            if (request.secretEnv != null) it[DeploymentJobs.secretEnvEncrypted] = newSecrets
            it[updatedAt] = LocalDateTime.now()
        }
        true
    }

    fun configurationExists(id: UUID): Boolean = transaction { DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq id }.count() > 0 }

    fun latestForProject(slug: String): DeploymentJobRecord? = transaction {
        DeploymentJobs.selectAll().where { DeploymentJobs.projectSlug eq slug }
            .orderBy(DeploymentJobs.createdAt to SortOrder.DESC).limit(1).singleOrNull()?.toRecord()
    }
    fun list(limit: Int, offset: Int): List<DeploymentJobRecord> = transaction {
        DeploymentJobs.selectAll().orderBy(DeploymentJobs.createdAt to SortOrder.DESC).limit(limit, offset.toLong()).map { it.toRecord() }
    }

    fun cancel(id: UUID): Boolean = transaction {
        val changed = DeploymentJobs.update({ (DeploymentJobs.id eq id) and (DeploymentJobs.status inList listOf("queued", "running", "awaiting_build", "awaiting_container")) }) {
            it[status] = "cancelled"; it[currentStep] = "cancelled"; it[completedAt] = LocalDateTime.now(); it[cancelledAt] = LocalDateTime.now(); it[updatedAt] = LocalDateTime.now()
        } > 0
        if (changed) DeploymentExecutions.update({ DeploymentExecutions.id eq id }) {
            it[status] = "cancelled"; it[currentStep] = "cancelled"; it[completedAt] = LocalDateTime.now(); it[cancelledAt] = LocalDateTime.now(); it[updatedAt] = LocalDateTime.now()
        }
        changed
    }

    fun retry(id: UUID): Boolean = transaction {
        val changed = DeploymentJobs.update({ DeploymentJobs.id eq id and (DeploymentJobs.status inList listOf("failed", "cancelled")) }) {
            it[status] = "queued"; it[currentStep] = "queued"; it[errorMessage] = null; it[completedAt] = null; it[updatedAt] = LocalDateTime.now()
        } > 0
        if (changed) DeploymentExecutions.update({ DeploymentExecutions.id eq id }) {
            it[status] = "queued"; it[currentStep] = "queued"; it[errorMessage] = null; it[completedAt] = null; it[updatedAt] = LocalDateTime.now()
        }
        changed
    }
    fun create(request: com.gatekeeper.deployment.CreateDeploymentRequest): UUID = transaction {
        val id = UUID.randomUUID()
        val envJson = Json.encodeToString(request.env)
        val secretCiphertext = request.secretEnv.takeIf { it.isNotEmpty() }?.let { values ->
            check(SecretValueCipher.isConfigured()) { "Deployment secret encryption is not configured" }
            SecretValueCipher.encrypt(Json.encodeToString(values))
        }
        val volumesJson = Json.encodeToString(request.volumes)
        // Keep the legacy row during rollout for worker compatibility. The new rows are the
        // durable source of configuration and the immutable execution snapshot.
        DeploymentConfigurations.insert {
            it[DeploymentConfigurations.id] = id
            it[repository] = request.repository; it[gitRef] = request.gitRef; it[registry] = request.registry
            it[imageName] = request.imageName; it[imageTag] = request.imageTag; it[containerName] = request.containerName
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort; it[network] = request.network
            it[restartPolicy] = request.restartPolicy; it[DeploymentConfigurations.envJson] = envJson
            it[DeploymentConfigurations.secretEnvEncrypted] = secretCiphertext; it[DeploymentConfigurations.volumesJson] = volumesJson
            it[createNetworkIfMissing] = request.createNetworkIfMissing; it[projectSlug] = request.projectSlug
        }
        DeploymentExecutions.insert {
            it[DeploymentExecutions.id] = id; it[configurationId] = id
            it[repository] = request.repository; it[gitRef] = request.gitRef; it[registry] = request.registry
            it[imageName] = request.imageName; it[imageTag] = request.imageTag; it[containerName] = request.containerName
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort; it[network] = request.network
            it[restartPolicy] = request.restartPolicy; it[DeploymentExecutions.envJson] = envJson
            it[DeploymentExecutions.secretEnvEncrypted] = secretCiphertext; it[DeploymentExecutions.volumesJson] = volumesJson
            it[createNetworkIfMissing] = request.createNetworkIfMissing; it[projectSlug] = request.projectSlug
            it[triggerSource] = request.triggerSource; it[status] = "queued"; it[currentStep] = "queued"
        }
        DeploymentJobs.insert {
            it[DeploymentJobs.id] = id
            it[repository] = request.repository
            it[gitRef] = request.gitRef
            it[registry] = request.registry
            it[imageName] = request.imageName
            it[imageTag] = request.imageTag
            it[containerName] = request.containerName
            it[hostPort] = request.hostPort
            it[containerPort] = request.containerPort
            it[network] = request.network
            it[restartPolicy] = request.restartPolicy
            it[DeploymentJobs.envJson] = envJson
            it[DeploymentJobs.secretEnvEncrypted] = secretCiphertext
            it[DeploymentJobs.volumesJson] = volumesJson
            it[DeploymentJobs.createNetworkIfMissing] = request.createNetworkIfMissing
            it[DeploymentJobs.projectSlug] = request.projectSlug
            it[DeploymentJobs.triggerSource] = request.triggerSource
            it[DeploymentJobs.status] = "queued"
            it[DeploymentJobs.currentStep] = "queued"
        }
        id
    }

    fun find(id: UUID): DeploymentJobRecord? = transaction {
        DeploymentJobs.selectAll().where { DeploymentJobs.id eq id }.singleOrNull()?.toRecord()
    }

    fun claimNext(): DeploymentJobRecord? = transaction {
        val row = DeploymentJobs.selectAll().where { DeploymentJobs.status eq "queued" }
            .orderBy(DeploymentJobs.createdAt to SortOrder.ASC).limit(1).singleOrNull() ?: return@transaction null
        val now = LocalDateTime.now()
        DeploymentJobs.update({ DeploymentJobs.id eq row[DeploymentJobs.id] }) {
            it[status] = "running"; it[currentStep] = "starting"; it[startedAt] = now; it[updatedAt] = now
        }
        DeploymentExecutions.update({ DeploymentExecutions.id eq row[DeploymentJobs.id] }) {
            it[status] = "running"; it[currentStep] = "starting"; it[startedAt] = now; it[updatedAt] = now
        }
        find(row[DeploymentJobs.id])
    }

    fun recoverStale(maxAgeMinutes: Long): Int = transaction {
        val cutoff = LocalDateTime.now().minusMinutes(maxAgeMinutes)
        val changed = DeploymentJobs.update({ (DeploymentJobs.status eq "running") and (DeploymentJobs.updatedAt less cutoff) }) {
            it[status] = "queued"; it[currentStep] = "recovered"; it[errorMessage] = "Recovered after worker restart or timeout"; it[updatedAt] = LocalDateTime.now()
        }
        DeploymentExecutions.update({ (DeploymentExecutions.status eq "running") and (DeploymentExecutions.updatedAt less cutoff) }) {
            it[status] = "queued"; it[currentStep] = "recovered"; it[errorMessage] = "Recovered after worker restart or timeout"; it[updatedAt] = LocalDateTime.now()
        }
        changed
    }

    fun isCancelled(id: UUID): Boolean = transaction {
        DeploymentJobs.selectAll().where { DeploymentJobs.id eq id }.singleOrNull()?.get(DeploymentJobs.status) == "cancelled"
    }

    fun update(id: UUID, step: String? = null, log: String? = null, commitSha: String? = null, imageDigest: String? = null, status: String? = null, error: String? = null) = transaction {
        val existing = DeploymentJobs.selectAll().where { DeploymentJobs.id eq id }.singleOrNull() ?: return@transaction
        DeploymentJobs.update({ DeploymentJobs.id eq id }) {
            step?.let { value -> it[currentStep] = value }
            val secrets = existing[DeploymentJobs.secretEnvEncrypted]?.let { encoded ->
                Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(encoded)).values
            }.orEmpty()
            log?.let { value -> it[logs] = existing[DeploymentJobs.logs] + SecretValueCipher.redact(value, secrets) + "\n" }
            commitSha?.let { value -> it[DeploymentJobs.commitSha] = value }
            imageDigest?.let { value -> it[DeploymentJobs.imageDigest] = value }
            status?.let { value -> it[DeploymentJobs.status] = value }
            error?.let { value -> it[errorMessage] = SecretValueCipher.redact(value, secrets) }
            if (status == "succeeded" || status == "failed") it[completedAt] = LocalDateTime.now()
            it[updatedAt] = LocalDateTime.now()
        }
        DeploymentExecutions.update({ DeploymentExecutions.id eq id }) {
            step?.let { value -> it[currentStep] = value }
            val secrets = existing[DeploymentJobs.secretEnvEncrypted]?.let { encoded -> Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(encoded)).values }.orEmpty()
            log?.let { value -> it[logs] = (DeploymentExecutions.selectAll().where { DeploymentExecutions.id eq id }.singleOrNull()?.get(DeploymentExecutions.logs).orEmpty()) + SecretValueCipher.redact(value, secrets) + "\n" }
            commitSha?.let { value -> it[DeploymentExecutions.commitSha] = value }
            imageDigest?.let { value -> it[DeploymentExecutions.imageDigest] = value }
            status?.let { value -> it[DeploymentExecutions.status] = value }
            error?.let { value -> it[DeploymentExecutions.errorMessage] = SecretValueCipher.redact(value, secrets) }
            if (status == "succeeded" || status == "failed") it[completedAt] = LocalDateTime.now()
            it[updatedAt] = LocalDateTime.now()
        }
    }

    fun setPreviousContainer(id: UUID, name: String?, image: String?) = transaction {
        DeploymentJobs.update({ DeploymentJobs.id eq id }) { it[previousContainerName] = name; it[previousImage] = image; it[updatedAt] = LocalDateTime.now() }
        DeploymentExecutions.update({ DeploymentExecutions.id eq id }) { it[previousContainerName] = name; it[previousImage] = image; it[updatedAt] = LocalDateTime.now() }
    }

    private fun ResultRow.toRecord() = DeploymentJobRecord(
        this[DeploymentJobs.id], this[DeploymentJobs.repository], this[DeploymentJobs.gitRef], this[DeploymentJobs.registry],
        this[DeploymentJobs.imageName], this[DeploymentJobs.imageTag], this[DeploymentJobs.containerName], this[DeploymentJobs.hostPort], this[DeploymentJobs.containerPort], this[DeploymentJobs.network], this[DeploymentJobs.restartPolicy], runCatching { Json.decodeFromString<Map<String, String>>(this[DeploymentJobs.envJson]) }.getOrDefault(emptyMap()), this[DeploymentJobs.secretEnvEncrypted]?.let { Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(it)) }.orEmpty(), runCatching { Json.decodeFromString<List<VolumeMount>>(this[DeploymentJobs.volumesJson]) }.getOrDefault(emptyList()), this[DeploymentJobs.createNetworkIfMissing], this[DeploymentJobs.status], this[DeploymentJobs.currentStep],
        this[DeploymentJobs.logs], this[DeploymentJobs.commitSha], this[DeploymentJobs.imageDigest], this[DeploymentJobs.errorMessage],
        this[DeploymentJobs.createdAt], this[DeploymentJobs.startedAt], this[DeploymentJobs.completedAt], this[DeploymentJobs.updatedAt], this[DeploymentJobs.previousContainerName], this[DeploymentJobs.previousImage], this[DeploymentJobs.projectSlug], this[DeploymentJobs.triggerSource]
    )
}
