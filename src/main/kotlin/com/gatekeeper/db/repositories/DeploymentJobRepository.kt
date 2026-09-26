package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.DeploymentJobs
import com.gatekeeper.db.tables.DeploymentConfigurations
import com.gatekeeper.db.tables.DeploymentExecutions
import com.gatekeeper.db.tables.Projects
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
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.db.tables.DeploymentStatus
import com.gatekeeper.db.tables.ProjectSecretSetVersions

private data class SecretSetReference(val id: UUID, val version: Int)

data class DeploymentJobRecord(
    val id: UUID, val repository: String, val gitRef: String, val registry: String,
    val imageName: String, val imageTag: String, val containerName: String?, val hostPort: Int?, val containerPort: Int?, val network: String, val restartPolicy: String, val env: Map<String, String>, val secretEnv: Map<String, String>, val volumes: List<VolumeMount>, val createNetworkIfMissing: Boolean,
    val status: String, val currentStep: String,
    val logs: String, val commitSha: String?, val imageDigest: String?, val errorMessage: String?,
    val createdAt: LocalDateTime, val startedAt: LocalDateTime?, val completedAt: LocalDateTime?, val updatedAt: LocalDateTime,
    val previousContainerName: String?, val previousImage: String?, val projectSlug: String?, val projectId: UUID?, val triggerSource: String,
    val environment: String = "production",
    val readinessType: String? = null,
    val readinessTarget: String? = null,
    val readinessTimeoutSeconds: Int = 60,
    val readinessIntervalSeconds: Int = 2,
    val readinessProbeTimeoutMillis: Int = 1000
)

object DeploymentJobRepository {
    fun createConfiguration(projectId: UUID, request: com.gatekeeper.deployment.CreateDeploymentRequest): UUID = transaction {
        val project = Projects.selectAll().where { (Projects.id eq projectId) and Projects.deletedAt.isNull() }.singleOrNull()
            ?: error("Project not found")
        val environment = requireEnvironment(request.environment)
        validateReadiness(request.readinessType, request.readinessTarget, request.readinessTimeoutSeconds, request.readinessIntervalSeconds, request.readinessProbeTimeoutMillis)
        check(DeploymentConfigurations.selectAll().where {
            (DeploymentConfigurations.projectId eq projectId) or
                (DeploymentConfigurations.projectId.isNull() and (DeploymentConfigurations.projectSlug eq project[Projects.slug]))
        }.none { (it[DeploymentConfigurations.environment] ?: "production") == environment }) {
            "Project already has a deployment configuration"
        }
        val id = UUID.randomUUID()
        val envJson = Json.encodeToString(request.env)
        val secretCiphertext = request.secretEnv.takeIf { it.isNotEmpty() }?.let { values ->
            check(SecretValueCipher.isConfigured()) { "Deployment secret encryption is not configured" }
            SecretValueCipher.encrypt(Json.encodeToString(values))
        }
        val secretSet = request.secretEnv.takeIf { it.isNotEmpty() }?.let { values ->
            createSecretSetVersion(projectId, environment, values, "admin")
        }
        DeploymentConfigurations.insert {
            it[DeploymentConfigurations.id] = id
            it[repository] = request.repository; it[gitRef] = request.gitRef; it[registry] = request.registry
            it[imageName] = request.imageName; it[imageTag] = request.imageTag; it[containerName] = request.containerName
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort; it[network] = request.network
            it[restartPolicy] = request.restartPolicy; it[DeploymentConfigurations.envJson] = envJson
            it[DeploymentConfigurations.secretEnvEncrypted] = secretCiphertext
            it[DeploymentConfigurations.secretSetId] = secretSet?.id
            it[DeploymentConfigurations.secretSetVersion] = secretSet?.version
            it[DeploymentConfigurations.volumesJson] = Json.encodeToString(request.volumes)
            it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[projectSlug] = project[Projects.slug]
            it[DeploymentConfigurations.projectId] = projectId
            it[DeploymentConfigurations.environment] = environment
            it[DeploymentConfigurations.readinessType] = request.readinessType?.lowercase()
            it[DeploymentConfigurations.readinessTarget] = request.readinessTarget
            it[DeploymentConfigurations.readinessTimeoutSeconds] = request.readinessTimeoutSeconds
            it[DeploymentConfigurations.readinessIntervalSeconds] = request.readinessIntervalSeconds
            it[DeploymentConfigurations.readinessProbeTimeoutMillis] = request.readinessProbeTimeoutMillis
        }
        id
    }

    fun updateConfiguration(id: UUID, request: UpdateDeploymentConfigurationRequest): Boolean = transaction {
        val row = DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq id }.singleOrNull() ?: return@transaction false
        val projectId = row[DeploymentConfigurations.projectId] ?: resolveProjectId(row[DeploymentConfigurations.projectSlug])
        val newSecrets = request.secretEnv?.let { values ->
            check(SecretValueCipher.isConfigured()) { "Deployment secret encryption is not configured" }
            SecretValueCipher.encrypt(Json.encodeToString(values))
        }
        val envJson = request.env?.let(Json::encodeToString)
        val volumesJson = request.volumes?.let(Json::encodeToString)
        val environment = request.environment?.let(::requireEnvironment)
        val currentEnvironment = row[DeploymentConfigurations.environment] ?: "production"
        val targetEnvironment = environment ?: currentEnvironment
        val environmentChanged = targetEnvironment != currentEnvironment
        val secretsForNewVersion = request.secretEnv ?: if (environmentChanged) {
            row[DeploymentConfigurations.secretEnvEncrypted]?.let { encrypted ->
                Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(encrypted))
            }.orEmpty()
        } else null
        val hasExistingSecretSnapshot = row[DeploymentConfigurations.secretSetId] != null ||
            row[DeploymentConfigurations.secretEnvEncrypted] != null
        val shouldCreateSecretVersion = request.secretEnv != null || (environmentChanged && hasExistingSecretSnapshot)
        val newSecretSet = if (shouldCreateSecretVersion) {
            projectId?.let { createSecretSetVersion(it, targetEnvironment, secretsForNewVersion.orEmpty(), "admin") }
        } else null
        val readinessType = request.readinessType?.let { it.trim().lowercase().also { type -> require(type in supportedReadinessTypes) { "Readiness type must be docker, http, tcp, or process" } } }
        val readinessTarget = request.readinessTarget?.also { require(it.isNotBlank()) { "Readiness target must not be blank" } }
        request.readinessTimeoutSeconds?.let { require(it in 1..600) { "Readiness timeout must be between 1 and 600 seconds" } }
        request.readinessIntervalSeconds?.let { require(it in 1..30) { "Readiness interval must be between 1 and 30 seconds" } }
        request.readinessProbeTimeoutMillis?.let { require(it in 100..30000) { "Readiness probe timeout must be between 100 and 30000 milliseconds" } }
        DeploymentConfigurations.update({ DeploymentConfigurations.id eq id }) {
            request.repository?.let { value -> it[repository] = value }; request.gitRef?.let { value -> it[gitRef] = value }
            request.registry?.let { value -> it[registry] = value }; request.imageName?.let { value -> it[imageName] = value }
            request.imageTag?.let { value -> it[imageTag] = value }; request.containerName?.let { value -> it[containerName] = value }
            request.hostPort?.let { value -> it[hostPort] = value }; request.containerPort?.let { value -> it[containerPort] = value }
            request.network?.let { value -> it[network] = value }; request.restartPolicy?.let { value -> it[restartPolicy] = value }
            envJson?.let { value -> it[DeploymentConfigurations.envJson] = value }; volumesJson?.let { value -> it[DeploymentConfigurations.volumesJson] = value }
            request.createNetworkIfMissing?.let { value -> it[createNetworkIfMissing] = value }
            newSecrets?.let { value -> it[secretEnvEncrypted] = value }
            if (shouldCreateSecretVersion) {
                it[DeploymentConfigurations.secretSetId] = newSecretSet?.id
                it[DeploymentConfigurations.secretSetVersion] = newSecretSet?.version
            }
            environment?.let { value -> it[DeploymentConfigurations.environment] = value }
            readinessType?.let { value -> it[DeploymentConfigurations.readinessType] = value }
            readinessTarget?.let { value -> it[DeploymentConfigurations.readinessTarget] = value }
            request.readinessTimeoutSeconds?.let { value -> it[DeploymentConfigurations.readinessTimeoutSeconds] = value }
            request.readinessIntervalSeconds?.let { value -> it[DeploymentConfigurations.readinessIntervalSeconds] = value }
            request.readinessProbeTimeoutMillis?.let { value -> it[DeploymentConfigurations.readinessProbeTimeoutMillis] = value }
            projectId?.let { value -> it[DeploymentConfigurations.projectId] = value }
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
            newSecrets?.let { value -> it[DeploymentJobs.secretEnvEncrypted] = value }
            environment?.let { value -> it[DeploymentJobs.environment] = value }
            readinessType?.let { value -> it[DeploymentJobs.readinessType] = value }
            readinessTarget?.let { value -> it[DeploymentJobs.readinessTarget] = value }
            request.readinessTimeoutSeconds?.let { value -> it[DeploymentJobs.readinessTimeoutSeconds] = value }
            request.readinessIntervalSeconds?.let { value -> it[DeploymentJobs.readinessIntervalSeconds] = value }
            request.readinessProbeTimeoutMillis?.let { value -> it[DeploymentJobs.readinessProbeTimeoutMillis] = value }
            projectId?.let { value -> it[DeploymentJobs.projectId] = value }
            it[updatedAt] = LocalDateTime.now()
        }
        DeploymentExecutions.update({ DeploymentExecutions.id eq id }) {
            projectId?.let { value -> it[DeploymentExecutions.projectId] = value }
            environment?.let { value -> it[DeploymentExecutions.environment] = value }
            readinessType?.let { value -> it[DeploymentExecutions.readinessType] = value }
            readinessTarget?.let { value -> it[DeploymentExecutions.readinessTarget] = value }
            request.readinessTimeoutSeconds?.let { value -> it[DeploymentExecutions.readinessTimeoutSeconds] = value }
            request.readinessIntervalSeconds?.let { value -> it[DeploymentExecutions.readinessIntervalSeconds] = value }
            request.readinessProbeTimeoutMillis?.let { value -> it[DeploymentExecutions.readinessProbeTimeoutMillis] = value }
        }
        true
    }

    fun configurationExists(id: UUID): Boolean = transaction { DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq id }.count() > 0 }

    fun redeployConfiguration(id: UUID): UUID? {
        val prepared = transaction {
            val row = DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq id }.singleOrNull() ?: return@transaction null
            val projectId = row[DeploymentConfigurations.projectId] ?: resolveProjectId(row[DeploymentConfigurations.projectSlug])
            val projectSlug = projectId?.let { project ->
                Projects.selectAll().where { (Projects.id eq project) and Projects.deletedAt.isNull() }.singleOrNull()?.get(Projects.slug)
            } ?: row[DeploymentConfigurations.projectSlug]
            val request = com.gatekeeper.deployment.CreateDeploymentRequest(
                repository = row[DeploymentConfigurations.repository],
                gitRef = row[DeploymentConfigurations.gitRef],
                registry = row[DeploymentConfigurations.registry],
                imageName = row[DeploymentConfigurations.imageName],
                imageTag = row[DeploymentConfigurations.imageTag],
                containerName = row[DeploymentConfigurations.containerName],
                hostPort = row[DeploymentConfigurations.hostPort],
                containerPort = row[DeploymentConfigurations.containerPort],
                network = row[DeploymentConfigurations.network],
                restartPolicy = row[DeploymentConfigurations.restartPolicy],
                projectSlug = projectSlug,
                triggerSource = "manual_redeploy",
                env = runCatching { Json.decodeFromString<Map<String, String>>(row[DeploymentConfigurations.envJson]) }.getOrDefault(emptyMap()),
                secretEnv = row[DeploymentConfigurations.secretEnvEncrypted]?.let { encrypted ->
                    Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(encrypted))
                }.orEmpty(),
                volumes = runCatching { Json.decodeFromString<List<VolumeMount>>(row[DeploymentConfigurations.volumesJson]) }.getOrDefault(emptyList()),
                createNetworkIfMissing = row[DeploymentConfigurations.createNetworkIfMissing],
                environment = row[DeploymentConfigurations.environment] ?: "production",
                readinessType = row[DeploymentConfigurations.readinessType],
                readinessTarget = row[DeploymentConfigurations.readinessTarget],
                readinessTimeoutSeconds = row[DeploymentConfigurations.readinessTimeoutSeconds],
                readinessIntervalSeconds = row[DeploymentConfigurations.readinessIntervalSeconds],
                readinessProbeTimeoutMillis = row[DeploymentConfigurations.readinessProbeTimeoutMillis]
            )
            var secretSet = row[DeploymentConfigurations.secretSetId]?.let { secretId ->
                row[DeploymentConfigurations.secretSetVersion]?.let { version -> SecretSetReference(secretId, version) }
            }
            if (secretSet == null && projectId != null && request.secretEnv.isNotEmpty()) {
                val createdSecretSet = createSecretSetVersion(projectId, request.environment, request.secretEnv, "manual_redeploy")
                secretSet = createdSecretSet
                DeploymentConfigurations.update({ DeploymentConfigurations.id eq id }) {
                    it[DeploymentConfigurations.secretSetId] = createdSecretSet.id
                    it[DeploymentConfigurations.secretSetVersion] = createdSecretSet.version
                }
            }
            request to secretSet
        } ?: return null
        return create(prepared.first, prepared.second)
    }

    fun latestForProject(projectId: UUID, slug: String): DeploymentJobRecord? = transaction {
        val projectOwned = DeploymentJobs.selectAll().where { DeploymentJobs.projectId eq projectId }
            .orderBy(DeploymentJobs.createdAt to SortOrder.DESC).limit(1).singleOrNull()
        val row = projectOwned ?: DeploymentJobs.selectAll().where {
            (DeploymentJobs.projectId.isNull()) and (DeploymentJobs.projectSlug eq slug)
        }.orderBy(DeploymentJobs.createdAt to SortOrder.DESC).limit(1).singleOrNull()
        row?.toRecord()
    }
    fun list(limit: Int, offset: Int): List<DeploymentJobRecord> = transaction {
        DeploymentJobs.selectAll().orderBy(DeploymentJobs.createdAt to SortOrder.DESC).limit(limit, offset.toLong()).map { it.toRecord() }
    }

    fun cancel(id: UUID): Boolean = transaction {
        val changed = DeploymentJobs.update({ (DeploymentJobs.id eq id) and (DeploymentJobs.status inList listOf("queued", "running", "awaiting_build", "awaiting_container")) }) {
            it[status] = "cancelled"; it[currentStep] = "cancelled"; it[completedAt] = LocalDateTime.now(); it[cancelledAt] = LocalDateTime.now(); it[updatedAt] = LocalDateTime.now()
        } > 0
        if (changed) DeploymentApplicationService.transition(id, DeploymentStatus.CANCELLED)
        if (changed) DeploymentExecutions.update({ DeploymentExecutions.id eq id }) {
            it[status] = "cancelled"; it[currentStep] = "cancelled"; it[completedAt] = LocalDateTime.now(); it[cancelledAt] = LocalDateTime.now(); it[updatedAt] = LocalDateTime.now()
        }
        changed
    }

    fun retry(id: UUID): Boolean = transaction {
        val changed = DeploymentJobs.update({ DeploymentJobs.id eq id and (DeploymentJobs.status inList listOf("failed", "cancelled")) }) {
            it[status] = "queued"; it[currentStep] = "queued"; it[errorMessage] = null; it[completedAt] = null; it[updatedAt] = LocalDateTime.now()
        } > 0
        if (changed) DeploymentApplicationService.transition(id, DeploymentStatus.QUEUED)
        if (changed) DeploymentExecutions.update({ DeploymentExecutions.id eq id }) {
            it[status] = "queued"; it[currentStep] = "queued"; it[errorMessage] = null; it[completedAt] = null; it[updatedAt] = LocalDateTime.now()
        }
        changed
    }
    fun create(request: com.gatekeeper.deployment.CreateDeploymentRequest): UUID = create(request, null)

    private fun create(request: com.gatekeeper.deployment.CreateDeploymentRequest, existingSecretSet: SecretSetReference?): UUID = transaction {
        val id = UUID.randomUUID()
        val projectId = resolveProjectId(request.projectSlug)
        val environment = requireEnvironment(request.environment)
        validateReadiness(request.readinessType, request.readinessTarget, request.readinessTimeoutSeconds, request.readinessIntervalSeconds, request.readinessProbeTimeoutMillis)
        val envJson = Json.encodeToString(request.env)
        val secretCiphertext = request.secretEnv.takeIf { it.isNotEmpty() }?.let { values ->
            check(SecretValueCipher.isConfigured()) { "Deployment secret encryption is not configured" }
            SecretValueCipher.encrypt(Json.encodeToString(values))
        }
        val secretSet = existingSecretSet ?: request.secretEnv.takeIf { it.isNotEmpty() }?.let { values ->
            projectId?.let { createSecretSetVersion(it, environment, values, request.triggerSource) }
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
            it[DeploymentConfigurations.secretSetId] = secretSet?.id
            it[DeploymentConfigurations.secretSetVersion] = secretSet?.version
            it[createNetworkIfMissing] = request.createNetworkIfMissing; it[projectSlug] = request.projectSlug
            it[DeploymentConfigurations.projectId] = projectId
            it[DeploymentConfigurations.environment] = environment
            it[DeploymentConfigurations.readinessType] = request.readinessType?.lowercase()
            it[DeploymentConfigurations.readinessTarget] = request.readinessTarget
            it[DeploymentConfigurations.readinessTimeoutSeconds] = request.readinessTimeoutSeconds
            it[DeploymentConfigurations.readinessIntervalSeconds] = request.readinessIntervalSeconds
            it[DeploymentConfigurations.readinessProbeTimeoutMillis] = request.readinessProbeTimeoutMillis
        }
        DeploymentExecutions.insert {
            it[DeploymentExecutions.id] = id; it[configurationId] = id
            it[repository] = request.repository; it[gitRef] = request.gitRef; it[registry] = request.registry
            it[imageName] = request.imageName; it[imageTag] = request.imageTag; it[containerName] = request.containerName
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort; it[network] = request.network
            it[restartPolicy] = request.restartPolicy; it[DeploymentExecutions.envJson] = envJson
            it[DeploymentExecutions.secretEnvEncrypted] = secretCiphertext; it[DeploymentExecutions.volumesJson] = volumesJson
            it[DeploymentExecutions.secretSetId] = secretSet?.id
            it[DeploymentExecutions.secretSetVersion] = secretSet?.version
            it[createNetworkIfMissing] = request.createNetworkIfMissing; it[projectSlug] = request.projectSlug
            it[DeploymentExecutions.projectId] = projectId
            it[DeploymentExecutions.environment] = environment
            it[DeploymentExecutions.readinessType] = request.readinessType?.lowercase()
            it[DeploymentExecutions.readinessTarget] = request.readinessTarget
            it[DeploymentExecutions.readinessTimeoutSeconds] = request.readinessTimeoutSeconds
            it[DeploymentExecutions.readinessIntervalSeconds] = request.readinessIntervalSeconds
            it[DeploymentExecutions.readinessProbeTimeoutMillis] = request.readinessProbeTimeoutMillis
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
            it[DeploymentJobs.projectId] = projectId
            it[DeploymentJobs.environment] = environment
            it[DeploymentJobs.readinessType] = request.readinessType?.lowercase()
            it[DeploymentJobs.readinessTarget] = request.readinessTarget
            it[DeploymentJobs.readinessTimeoutSeconds] = request.readinessTimeoutSeconds
            it[DeploymentJobs.readinessIntervalSeconds] = request.readinessIntervalSeconds
            it[DeploymentJobs.readinessProbeTimeoutMillis] = request.readinessProbeTimeoutMillis
            it[DeploymentJobs.triggerSource] = request.triggerSource
            it[DeploymentJobs.status] = "queued"
            it[DeploymentJobs.currentStep] = "queued"
        }
        DeploymentApplicationService.createQueued(
            id = id,
            projectId = projectId,
            configurationId = id,
            executionId = id,
            triggerSource = request.triggerSource,
            environment = environment,
            secretSetId = secretSet?.id,
            secretSetVersion = secretSet?.version
        )
        id
    }

    fun createRollback(targetDeploymentId: UUID): UUID? = transaction {
        val target = com.gatekeeper.db.tables.Deployments.selectAll()
            .where { com.gatekeeper.db.tables.Deployments.id eq targetDeploymentId }.singleOrNull()
            ?: return@transaction null
        require(target[com.gatekeeper.db.tables.Deployments.status] in setOf(DeploymentStatus.SUPERSEDED, DeploymentStatus.ROLLED_BACK)) {
            "Rollback target must be a superseded deployment"
        }
        val ownerId = target[com.gatekeeper.db.tables.Deployments.projectId]
        val environmentKey = target[com.gatekeeper.db.tables.Deployments.environment]
        check(ownerId != null && com.gatekeeper.db.tables.Deployments.selectAll().where {
            (com.gatekeeper.db.tables.Deployments.projectId eq ownerId) and
                (com.gatekeeper.db.tables.Deployments.environment eq environmentKey) and
                (com.gatekeeper.db.tables.Deployments.status eq DeploymentStatus.ACTIVE)
        }.count() == 1L) { "Rollback target must belong to the currently active project environment" }
        val execution = DeploymentExecutions.selectAll()
            .where { DeploymentExecutions.id eq target[com.gatekeeper.db.tables.Deployments.executionId] }.singleOrNull()
            ?: error("Rollback target execution snapshot not found")
        val secrets = execution[DeploymentExecutions.secretEnvEncrypted]?.let {
            Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(it))
        }.orEmpty()
        val secretSet = execution[DeploymentExecutions.secretSetId]?.let { secretId ->
            execution[DeploymentExecutions.secretSetVersion]?.let { version -> SecretSetReference(secretId, version) }
        } ?: secrets.takeIf { it.isNotEmpty() }?.let {
            createSecretSetVersion(ownerId, environmentKey, it, "rollback")
        }
        val request = com.gatekeeper.deployment.CreateDeploymentRequest(
            repository = execution[DeploymentExecutions.repository],
            gitRef = execution[DeploymentExecutions.gitRef],
            registry = execution[DeploymentExecutions.registry],
            imageName = execution[DeploymentExecutions.imageName],
            imageTag = execution[DeploymentExecutions.imageTag],
            containerName = execution[DeploymentExecutions.containerName],
            hostPort = execution[DeploymentExecutions.hostPort],
            containerPort = execution[DeploymentExecutions.containerPort],
            network = execution[DeploymentExecutions.network],
            restartPolicy = execution[DeploymentExecutions.restartPolicy],
            projectSlug = execution[DeploymentExecutions.projectSlug],
            triggerSource = "rollback",
            env = Json.decodeFromString<Map<String, String>>(execution[DeploymentExecutions.envJson]),
            secretEnv = secrets,
            volumes = Json.decodeFromString<List<VolumeMount>>(execution[DeploymentExecutions.volumesJson]),
            createNetworkIfMissing = execution[DeploymentExecutions.createNetworkIfMissing],
            environment = target[com.gatekeeper.db.tables.Deployments.environment],
            readinessType = execution[DeploymentExecutions.readinessType],
            readinessTarget = execution[DeploymentExecutions.readinessTarget],
            readinessTimeoutSeconds = execution[DeploymentExecutions.readinessTimeoutSeconds],
            readinessIntervalSeconds = execution[DeploymentExecutions.readinessIntervalSeconds],
            readinessProbeTimeoutMillis = execution[DeploymentExecutions.readinessProbeTimeoutMillis]
        )
        val id = UUID.randomUUID()
        val projectId = ownerId
        val environment = environmentKey
        val envJson = Json.encodeToString(request.env)
        val secretCiphertext = request.secretEnv.takeIf { it.isNotEmpty() }?.let {
            check(SecretValueCipher.isConfigured()) { "Deployment secret encryption is not configured" }
            SecretValueCipher.encrypt(Json.encodeToString(it))
        }
        val volumesJson = Json.encodeToString(request.volumes)
        DeploymentConfigurations.insert {
            it[DeploymentConfigurations.id] = id
            it[repository] = request.repository; it[gitRef] = request.gitRef; it[registry] = request.registry
            it[imageName] = request.imageName; it[imageTag] = request.imageTag; it[containerName] = request.containerName
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort
            it[network] = request.network; it[restartPolicy] = request.restartPolicy
            it[DeploymentConfigurations.envJson] = envJson; it[DeploymentConfigurations.secretEnvEncrypted] = secretCiphertext
            it[DeploymentConfigurations.secretSetId] = secretSet?.id
            it[DeploymentConfigurations.secretSetVersion] = secretSet?.version
            it[DeploymentConfigurations.volumesJson] = volumesJson; it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[projectSlug] = request.projectSlug; it[DeploymentConfigurations.projectId] = projectId
            it[DeploymentConfigurations.environment] = environment
            it[DeploymentConfigurations.readinessType] = request.readinessType
            it[DeploymentConfigurations.readinessTarget] = request.readinessTarget
            it[DeploymentConfigurations.readinessTimeoutSeconds] = request.readinessTimeoutSeconds
            it[DeploymentConfigurations.readinessIntervalSeconds] = request.readinessIntervalSeconds
            it[DeploymentConfigurations.readinessProbeTimeoutMillis] = request.readinessProbeTimeoutMillis
        }
        DeploymentExecutions.insert {
            it[DeploymentExecutions.id] = id; it[configurationId] = id
            it[repository] = request.repository; it[gitRef] = request.gitRef; it[registry] = request.registry
            it[imageName] = request.imageName; it[imageTag] = request.imageTag; it[containerName] = request.containerName
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort
            it[network] = request.network; it[restartPolicy] = request.restartPolicy
            it[DeploymentExecutions.envJson] = envJson; it[DeploymentExecutions.secretEnvEncrypted] = secretCiphertext
            it[DeploymentExecutions.secretSetId] = secretSet?.id
            it[DeploymentExecutions.secretSetVersion] = secretSet?.version
            it[DeploymentExecutions.volumesJson] = volumesJson; it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[projectSlug] = request.projectSlug; it[DeploymentExecutions.projectId] = projectId
            it[DeploymentExecutions.environment] = environment
            it[DeploymentExecutions.readinessType] = request.readinessType
            it[DeploymentExecutions.readinessTarget] = request.readinessTarget
            it[DeploymentExecutions.readinessTimeoutSeconds] = request.readinessTimeoutSeconds
            it[DeploymentExecutions.readinessIntervalSeconds] = request.readinessIntervalSeconds
            it[DeploymentExecutions.readinessProbeTimeoutMillis] = request.readinessProbeTimeoutMillis
            it[triggerSource] = "rollback"; it[status] = "queued"; it[currentStep] = "queued"
        }
        DeploymentJobs.insert {
            it[DeploymentJobs.id] = id
            it[repository] = request.repository; it[gitRef] = request.gitRef; it[registry] = request.registry
            it[imageName] = request.imageName; it[imageTag] = request.imageTag; it[containerName] = request.containerName
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort
            it[network] = request.network; it[restartPolicy] = request.restartPolicy
            it[DeploymentJobs.envJson] = envJson; it[DeploymentJobs.secretEnvEncrypted] = secretCiphertext
            it[DeploymentJobs.volumesJson] = volumesJson; it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[DeploymentJobs.projectSlug] = request.projectSlug; it[DeploymentJobs.projectId] = projectId
            it[DeploymentJobs.environment] = environment; it[DeploymentJobs.readinessType] = request.readinessType
            it[DeploymentJobs.readinessTarget] = request.readinessTarget
            it[DeploymentJobs.readinessTimeoutSeconds] = request.readinessTimeoutSeconds
            it[DeploymentJobs.readinessIntervalSeconds] = request.readinessIntervalSeconds
            it[DeploymentJobs.readinessProbeTimeoutMillis] = request.readinessProbeTimeoutMillis
            it[DeploymentJobs.triggerSource] = "rollback"; it[status] = "queued"; it[currentStep] = "queued"
        }
        DeploymentApplicationService.createQueued(
            id = id, projectId = projectId, configurationId = id, executionId = id,
            triggerSource = "rollback", environment = environment,
            rolledBackToDeploymentId = targetDeploymentId,
            secretSetId = secretSet?.id,
            secretSetVersion = secretSet?.version
        )
        id
    }

    private fun createSecretSetVersion(
        projectId: UUID,
        environment: String,
        values: Map<String, String>,
        createdBy: String
    ): SecretSetReference {
        check(SecretValueCipher.isConfigured()) { "Deployment secret encryption is not configured" }
        val nextVersion = (ProjectSecretSetVersions.selectAll().where {
            (ProjectSecretSetVersions.projectId eq projectId) and
                (ProjectSecretSetVersions.environment eq environment)
        }.map { it[ProjectSecretSetVersions.version] }.maxOrNull() ?: 0) + 1
        val id = UUID.randomUUID()
        ProjectSecretSetVersions.insert {
            it[ProjectSecretSetVersions.id] = id
            it[ProjectSecretSetVersions.projectId] = projectId
            it[ProjectSecretSetVersions.environment] = environment
            it[ProjectSecretSetVersions.version] = nextVersion
            it[ProjectSecretSetVersions.encryptedPayload] = SecretValueCipher.encrypt(Json.encodeToString(values))
            it[ProjectSecretSetVersions.createdAt] = LocalDateTime.now()
            it[ProjectSecretSetVersions.createdBy] = createdBy
        }
        return SecretSetReference(id, nextVersion)
    }

    fun find(id: UUID): DeploymentJobRecord? = transaction {
        DeploymentJobs.selectAll().where { DeploymentJobs.id eq id }.singleOrNull()?.toRecord()
    }

    fun claimNext(): DeploymentJobRecord? = transaction {
        val row = DeploymentJobs.selectAll().where {
            (DeploymentJobs.status eq "queued") or
                ((DeploymentJobs.status eq "running") and
                    (DeploymentJobs.currentStep inList listOf("readiness_succeeded", "cutover_in_progress")) and
                    (DeploymentJobs.updatedAt less LocalDateTime.now().minusSeconds(30)))
        }
            .orderBy(DeploymentJobs.createdAt to SortOrder.ASC).limit(1).singleOrNull() ?: return@transaction null
        val now = LocalDateTime.now()
        populateProjectId(row[DeploymentJobs.id], row[DeploymentJobs.projectSlug])
        val isReadyForCutover = row[DeploymentJobs.currentStep] in setOf("readiness_succeeded", "cutover_in_progress")
        DeploymentJobs.update({ DeploymentJobs.id eq row[DeploymentJobs.id] }) {
            it[status] = "running"; it[currentStep] = if (isReadyForCutover) "cutover_in_progress" else "building"; it[startedAt] = now; it[updatedAt] = now
        }
        DeploymentExecutions.update({ DeploymentExecutions.id eq row[DeploymentJobs.id] }) {
            it[status] = "running"; it[currentStep] = if (isReadyForCutover) "cutover_in_progress" else "building"; it[startedAt] = now; it[updatedAt] = now
        }
        if (!isReadyForCutover) DeploymentApplicationService.transition(row[DeploymentJobs.id], DeploymentStatus.BUILDING)
        find(row[DeploymentJobs.id])
    }

    fun recoverStale(maxAgeMinutes: Long): Int = transaction {
        val cutoff = LocalDateTime.now().minusMinutes(maxAgeMinutes)
        val changed = DeploymentJobs.update({
            (DeploymentJobs.status eq "running") and
                (DeploymentJobs.currentStep notInList listOf("readiness_succeeded", "cutover_in_progress")) and
                (DeploymentJobs.updatedAt less cutoff)
        }) {
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
        populateProjectId(id, existing[DeploymentJobs.projectSlug])
        val deploymentSecrets = existing[DeploymentJobs.secretEnvEncrypted]?.let { encoded ->
            Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(encoded)).values
        }.orEmpty()
        val redactedError = error?.let { SecretValueCipher.redact(it, deploymentSecrets) }
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
        val lifecycleTarget = when {
            status == "failed" -> DeploymentStatus.FAILED
            step == "starting_container" -> DeploymentStatus.STARTING
            step == "health_checking" -> DeploymentStatus.HEALTH_CHECKING
            step in setOf("cloning", "checked_out", "building", "pushing", "pulling") -> DeploymentStatus.BUILDING
            else -> null
        }
        lifecycleTarget?.let {
            DeploymentApplicationService.transition(id, it, failureReason = redactedError)
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
        this[DeploymentJobs.createdAt], this[DeploymentJobs.startedAt], this[DeploymentJobs.completedAt], this[DeploymentJobs.updatedAt], this[DeploymentJobs.previousContainerName], this[DeploymentJobs.previousImage], this[DeploymentJobs.projectSlug], this[DeploymentJobs.projectId] ?: resolveProjectId(this[DeploymentJobs.projectSlug]), this[DeploymentJobs.triggerSource], this[DeploymentJobs.environment] ?: "production",
        this[DeploymentJobs.readinessType], this[DeploymentJobs.readinessTarget], this[DeploymentJobs.readinessTimeoutSeconds], this[DeploymentJobs.readinessIntervalSeconds], this[DeploymentJobs.readinessProbeTimeoutMillis]
    )

    private fun resolveProjectId(slug: String?): UUID? = slug?.let { projectSlug ->
        Projects.selectAll().where { (Projects.slug eq projectSlug) and Projects.deletedAt.isNull() }
            .singleOrNull()?.get(Projects.id)
    }

    private fun requireEnvironment(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "Deployment environment must not be blank" }
    }

    private val supportedReadinessTypes = setOf("docker", "http", "tcp", "process")

    private fun validateReadiness(type: String?, target: String?, timeout: Int, interval: Int, probeTimeout: Int) {
        require(type == null || type.trim().lowercase() in supportedReadinessTypes) { "Readiness type must be docker, http, tcp, or process" }
        require(timeout in 1..600) { "Readiness timeout must be between 1 and 600 seconds" }
        require(interval in 1..30) { "Readiness interval must be between 1 and 30 seconds" }
        require(probeTimeout in 100..30000) { "Readiness probe timeout must be between 100 and 30000 milliseconds" }
        require(type == null || type.lowercase() == "process" || !target.isNullOrBlank() || type.lowercase() == "docker") {
            "Readiness target is required for HTTP or TCP probes"
        }
    }

    private fun populateProjectId(id: UUID, slug: String?) {
        val projectId = resolveProjectId(slug) ?: return
        DeploymentConfigurations.update({ (DeploymentConfigurations.id eq id) and DeploymentConfigurations.projectId.isNull() }) {
            it[DeploymentConfigurations.projectId] = projectId
        }
        DeploymentExecutions.update({ (DeploymentExecutions.id eq id) and DeploymentExecutions.projectId.isNull() }) {
            it[DeploymentExecutions.projectId] = projectId
        }
        DeploymentJobs.update({ (DeploymentJobs.id eq id) and DeploymentJobs.projectId.isNull() }) {
            it[DeploymentJobs.projectId] = projectId
        }
    }
}
