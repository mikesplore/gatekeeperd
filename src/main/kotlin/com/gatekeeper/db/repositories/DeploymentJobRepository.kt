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
import kotlinx.serialization.json.Json
import com.gatekeeper.security.SecretValueCipher
import com.gatekeeper.deployment.UpdateDeploymentConfigurationRequest
import com.gatekeeper.deployment.DeploymentApplicationService
import com.gatekeeper.db.tables.DeploymentStatus
import com.gatekeeper.db.tables.ProjectSecretSetVersions

private data class SecretSetReference(val id: UUID, val version: Int)

data class DeploymentJobRecord(
    val id: UUID, val repository: String?, val gitRef: String, val registry: String,
    val imageName: String, val imageTag: String, val hostPort: Int?, val containerPort: Int?, val network: String, val restartPolicy: String, val env: Map<String, String>, val secretEnv: Map<String, String>, val volumes: List<VolumeMount>, val createNetworkIfMissing: Boolean,
    val status: String, val currentStep: String,
    val logs: String, val commitSha: String?, val imageDigest: String?, val errorMessage: String?,
    val createdAt: LocalDateTime, val startedAt: LocalDateTime?, val completedAt: LocalDateTime?, val updatedAt: LocalDateTime,
    val projectId: UUID, val triggerSource: String,
    val environment: String = "production",
    val readinessType: String? = null,
    val readinessTarget: String? = null,
    val readinessTimeoutSeconds: Int = 60,
    val readinessIntervalSeconds: Int = 2,
    val readinessProbeTimeoutMillis: Int = 1000,
    val secretSetId: UUID? = null,
    val secretSetVersion: Int? = null
)

object DeploymentJobRepository {
    data class AutoDeployTarget(
        val projectId: UUID, val repository: String, val gitRef: String, val registry: String,
        val imageName: String, val imageTag: String, val environment: String
    )

    fun findAutoDeployTargets(repository: String, gitRef: String): List<AutoDeployTarget> = transaction {
        DeploymentConfigurations.selectAll().where {
            (DeploymentConfigurations.repository eq repository) and
                (DeploymentConfigurations.gitRef eq gitRef) and
                (DeploymentConfigurations.autoDeploy eq true)
        }.mapNotNull { row ->
            val projectId = row[DeploymentConfigurations.projectId]
            if (Projects.selectAll().where { (Projects.id eq projectId) and Projects.deletedAt.isNull() }.count() == 0L) return@mapNotNull null
            val savedRepository = row[DeploymentConfigurations.repository] ?: return@mapNotNull null
            AutoDeployTarget(
                projectId, savedRepository, row[DeploymentConfigurations.gitRef],
                row[DeploymentConfigurations.registry], row[DeploymentConfigurations.imageName],
                row[DeploymentConfigurations.imageTag], row[DeploymentConfigurations.environment] ?: "production"
            )
        }
    }

    data class ConfigurationSummary(
        val id: UUID,
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
        val secretSetId: UUID?,
        val secretSetVersion: Int?
    )

    fun configurationIdForProject(projectId: UUID, environment: String = "production"): UUID? = transaction {
        DeploymentConfigurations.selectAll().where { DeploymentConfigurations.projectId eq projectId }
            .toList().singleOrNull { (it[DeploymentConfigurations.environment] ?: "production") == environment }
            ?.get(DeploymentConfigurations.id)
    }

    fun configurationSummary(projectId: UUID, environment: String = "production"): ConfigurationSummary? = transaction {
        val id = configurationIdForProject(projectId, environment) ?: return@transaction null
        val row = DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq id }.singleOrNull()
            ?: return@transaction null
        val env = runCatching { Json.decodeFromString<Map<String, String>>(row[DeploymentConfigurations.envJson]) }.getOrDefault(emptyMap())
        ConfigurationSummary(
            id, row[DeploymentConfigurations.repository], row[DeploymentConfigurations.gitRef], row[DeploymentConfigurations.registry],
            row[DeploymentConfigurations.imageName], row[DeploymentConfigurations.imageTag], row[DeploymentConfigurations.autoDeploy], row[DeploymentConfigurations.containerPort],
            row[DeploymentConfigurations.hostPort], row[DeploymentConfigurations.network], row[DeploymentConfigurations.restartPolicy],
            row[DeploymentConfigurations.environment] ?: "production", env, env.keys.sorted(),
            row[DeploymentConfigurations.secretSetId], row[DeploymentConfigurations.secretSetVersion]
        )
    }

    fun upsertProjectConfiguration(projectId: UUID, request: com.gatekeeper.deployment.CreateDeploymentRequest): UUID {
        val currentId = configurationIdForProject(projectId, request.environment)
        if (currentId == null) return createConfiguration(projectId, request)
        val updated = updateConfiguration(currentId, com.gatekeeper.deployment.UpdateDeploymentConfigurationRequest(
            repository = request.repository, gitRef = request.gitRef, registry = request.registry,
            imageName = request.imageName, imageTag = request.imageTag,
            hostPort = request.hostPort, containerPort = request.containerPort, network = request.network,
            restartPolicy = request.restartPolicy, env = request.env, volumes = request.volumes,
            createNetworkIfMissing = request.createNetworkIfMissing, autoDeploy = request.autoDeploy, environment = request.environment,
            readinessType = request.readinessType, readinessTarget = request.readinessTarget,
            readinessTimeoutSeconds = request.readinessTimeoutSeconds,
            readinessIntervalSeconds = request.readinessIntervalSeconds,
            readinessProbeTimeoutMillis = request.readinessProbeTimeoutMillis
        ), replaceRepository = true)
        check(updated) { "Deployment configuration disappeared while saving" }
        return currentId
    }

    /** Atomically versions supplied secrets and queues a deployment from the saved configuration. */
    fun rotateProjectSecretsAndDeploy(projectId: UUID, secretEnv: Map<String, String>, actor: String): UUID? = transaction {
        val configurationId = configurationIdForProject(projectId) ?: return@transaction null
        val row = DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq configurationId }.singleOrNull()
            ?: return@transaction null
        val environment = row[DeploymentConfigurations.environment] ?: "production"
        val secretSet = createSecretSetVersion(projectId, environment, secretEnv, actor)
        DeploymentConfigurations.update({ DeploymentConfigurations.id eq configurationId }) {
            it[DeploymentConfigurations.secretSetId] = secretSet.id
            it[DeploymentConfigurations.secretSetVersion] = secretSet.version
            it[DeploymentConfigurations.secretEnvEncrypted] = SecretValueCipher.encrypt(Json.encodeToString(secretEnv))
            it[DeploymentConfigurations.updatedAt] = LocalDateTime.now()
        }
        DeploymentJobs.update({ DeploymentJobs.id eq configurationId }) {
            it[DeploymentJobs.secretEnvEncrypted] = SecretValueCipher.encrypt(Json.encodeToString(secretEnv))
            it[DeploymentJobs.updatedAt] = LocalDateTime.now()
        }
        val saved = DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq configurationId }.singleOrNull()
            ?: error("Deployment configuration disappeared during secret rotation")
        val request = com.gatekeeper.deployment.CreateDeploymentRequest(
                repository = saved[DeploymentConfigurations.repository], gitRef = saved[DeploymentConfigurations.gitRef],
                registry = saved[DeploymentConfigurations.registry], imageName = saved[DeploymentConfigurations.imageName],
                imageTag = saved[DeploymentConfigurations.imageTag],
                hostPort = saved[DeploymentConfigurations.hostPort], containerPort = saved[DeploymentConfigurations.containerPort],
                network = saved[DeploymentConfigurations.network], restartPolicy = saved[DeploymentConfigurations.restartPolicy],
                projectId = saved[DeploymentConfigurations.projectId]?.toString(), autoDeploy = saved[DeploymentConfigurations.autoDeploy],
                triggerSource = "secret_rotation",
                env = Json.decodeFromString(saved[DeploymentConfigurations.envJson]), secretEnv = secretEnv,
                volumes = Json.decodeFromString(saved[DeploymentConfigurations.volumesJson]),
                createNetworkIfMissing = saved[DeploymentConfigurations.createNetworkIfMissing], environment = environment,
                readinessType = saved[DeploymentConfigurations.readinessType], readinessTarget = saved[DeploymentConfigurations.readinessTarget],
                readinessTimeoutSeconds = saved[DeploymentConfigurations.readinessTimeoutSeconds],
                readinessIntervalSeconds = saved[DeploymentConfigurations.readinessIntervalSeconds],
                readinessProbeTimeoutMillis = saved[DeploymentConfigurations.readinessProbeTimeoutMillis]
            )
        create(request, SecretSetReference(secretSet.id, secretSet.version))
    }

    fun createConfiguration(projectId: UUID, request: com.gatekeeper.deployment.CreateDeploymentRequest): UUID = transaction {
        val project = Projects.selectAll().where { (Projects.id eq projectId) and Projects.deletedAt.isNull() }.singleOrNull()
            ?: error("Project not found")
        val environment = requireEnvironment(request.environment)
        validateReadiness(request.readinessType, request.readinessTarget, request.readinessTimeoutSeconds, request.readinessIntervalSeconds, request.readinessProbeTimeoutMillis)
        check(DeploymentConfigurations.selectAll().where {
            DeploymentConfigurations.projectId eq projectId
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
            it[imageName] = request.imageName; it[imageTag] = request.imageTag
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort; it[network] = request.network
            it[restartPolicy] = request.restartPolicy; it[DeploymentConfigurations.envJson] = envJson
            it[DeploymentConfigurations.secretEnvEncrypted] = secretCiphertext
            it[DeploymentConfigurations.secretSetId] = secretSet?.id
            it[DeploymentConfigurations.secretSetVersion] = secretSet?.version
            it[DeploymentConfigurations.volumesJson] = Json.encodeToString(request.volumes)
            it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[DeploymentConfigurations.autoDeploy] = request.autoDeploy
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

    data class AdoptedRuntimeRecord(val deploymentId: UUID, val configurationId: UUID)

    /** Records an existing running container as an immutable deployment snapshot; its environment is encrypted at rest. */
    fun createAdoptedRuntime(
        projectId: UUID,
        container: com.gatekeeper.docker.DockerService.AdoptionDetails,
        containerPort: Int,
        actor: String
    ): AdoptedRuntimeRecord = transaction {
        require(container.environment.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) { "Container has invalid environment variable names" }
        if (container.environment.isNotEmpty()) check(SecretValueCipher.isConfigured()) { "Secret encryption must be configured to adopt a container with environment variables" }
        val environment = "production"
        val (registry, imageName, imageTag) = splitImageReference(container.image)
        val configurationId = configurationIdForProject(projectId, environment) ?: createConfiguration(
            projectId,
            com.gatekeeper.deployment.CreateDeploymentRequest(
                registry = registry, imageName = imageName, imageTag = imageTag,
                containerPort = containerPort, network = container.networks.firstOrNull() ?: "bridge",
                restartPolicy = container.restartPolicy, projectId = projectId.toString(), environment = environment,
                triggerSource = "container_adoption", volumes = container.volumes
            )
        )
        val secretSet = container.environment.takeIf { it.isNotEmpty() }?.let { createSecretSetVersion(projectId, environment, it, actor) }
        val id = UUID.randomUUID()
        val encryptedEnvironment = container.environment.takeIf { it.isNotEmpty() }
            ?.let { SecretValueCipher.encrypt(Json.encodeToString(it)) }
        DeploymentConfigurations.update({ DeploymentConfigurations.id eq configurationId }) {
            it[DeploymentConfigurations.repository] = null
            it[DeploymentConfigurations.gitRef] = ""
            it[DeploymentConfigurations.registry] = registry
            it[DeploymentConfigurations.imageName] = imageName
            it[DeploymentConfigurations.imageTag] = imageTag
            it[DeploymentConfigurations.hostPort] = null
            it[DeploymentConfigurations.containerPort] = containerPort
            it[DeploymentConfigurations.network] = container.networks.firstOrNull() ?: "bridge"
            it[DeploymentConfigurations.restartPolicy] = container.restartPolicy
            it[DeploymentConfigurations.envJson] = "{}"
            it[DeploymentConfigurations.secretEnvEncrypted] = encryptedEnvironment
            it[DeploymentConfigurations.secretSetId] = secretSet?.id
            it[DeploymentConfigurations.secretSetVersion] = secretSet?.version
            it[DeploymentConfigurations.volumesJson] = Json.encodeToString(container.volumes)
            it[DeploymentConfigurations.autoDeploy] = false
            it[DeploymentConfigurations.readinessType] = "tcp"
            it[DeploymentConfigurations.readinessTarget] = containerPort.toString()
            it[DeploymentConfigurations.readinessTimeoutSeconds] = 60
            it[DeploymentConfigurations.readinessIntervalSeconds] = 2
            it[DeploymentConfigurations.readinessProbeTimeoutMillis] = 1000
            it[DeploymentConfigurations.updatedAt] = LocalDateTime.now()
        }
        val execution = DeploymentExecutions.insert {
            it[DeploymentExecutions.id] = id
            it[DeploymentExecutions.configurationId] = configurationId
            it[DeploymentExecutions.repository] = null
            it[DeploymentExecutions.gitRef] = ""
            it[DeploymentExecutions.registry] = registry
            it[DeploymentExecutions.imageName] = imageName
            it[DeploymentExecutions.imageTag] = imageTag
            it[DeploymentExecutions.hostPort] = null
            it[DeploymentExecutions.containerPort] = containerPort
            it[DeploymentExecutions.network] = container.networks.firstOrNull() ?: "bridge"
            it[DeploymentExecutions.restartPolicy] = container.restartPolicy
            it[DeploymentExecutions.envJson] = "{}"
            it[DeploymentExecutions.secretEnvEncrypted] = encryptedEnvironment
            it[DeploymentExecutions.secretSetId] = secretSet?.id
            it[DeploymentExecutions.secretSetVersion] = secretSet?.version
            it[DeploymentExecutions.volumesJson] = Json.encodeToString(container.volumes)
            it[DeploymentExecutions.createNetworkIfMissing] = false
            it[DeploymentExecutions.projectId] = projectId
            it[DeploymentExecutions.environment] = environment
            it[DeploymentExecutions.readinessType] = "tcp"
            it[DeploymentExecutions.readinessTarget] = containerPort.toString()
            it[DeploymentExecutions.readinessTimeoutSeconds] = 1
            it[DeploymentExecutions.readinessIntervalSeconds] = 1
            it[DeploymentExecutions.readinessProbeTimeoutMillis] = 1000
            it[DeploymentExecutions.triggerSource] = "container_adoption"
            it[DeploymentExecutions.status] = "succeeded"
            it[DeploymentExecutions.currentStep] = "readiness_succeeded"
            it[DeploymentExecutions.logs] = "Existing running container adopted; environment captured as encrypted secret-set version."
            it[DeploymentExecutions.imageDigest] = container.imageDigest
            it[DeploymentExecutions.completedAt] = LocalDateTime.now()
        }
        val mappedHostPort = container.ports[containerPort] ?: error("Selected container port is not published")
        check(DeploymentApplicationService.recordAdoptedRuntime(
            id = id, projectId = projectId, configurationId = configurationId, executionId = execution[DeploymentExecutions.id],
            environment = environment, triggerSource = "container_adoption", containerName = container.name,
            hostPort = mappedHostPort, containerPort = containerPort, portMappings = container.ports,
            imageDigest = container.imageDigest, secretSetId = secretSet?.id, secretSetVersion = secretSet?.version
        )) { "Unable to record adopted runtime" }
        AdoptedRuntimeRecord(id, configurationId)
    }

    private fun splitImageReference(reference: String): Triple<String, String, String> {
        val digestIndex = reference.indexOf('@')
        val withoutDigest = if (digestIndex >= 0) reference.substring(0, digestIndex) else reference
        val slash = withoutDigest.lastIndexOf('/')
        val colon = withoutDigest.lastIndexOf(':')
        val hasTag = colon > slash
        val taggedName = if (hasTag) withoutDigest.substring(0, colon) else withoutDigest
        val tag = if (hasTag) withoutDigest.substring(colon + 1) else "latest"
        val firstSlash = taggedName.indexOf('/')
        val firstPart = if (firstSlash >= 0) taggedName.substring(0, firstSlash) else ""
        val hasRegistry = firstPart.contains('.') || firstPart.contains(':') || firstPart == "localhost"
        val registry = if (hasRegistry) firstPart else "docker.io"
        val name = when {
            hasRegistry -> taggedName.substring(firstSlash + 1)
            taggedName.isNotBlank() -> taggedName
            else -> error("Container image reference is invalid")
        }
        return Triple(registry, name, tag)
    }

    fun updateConfiguration(id: UUID, request: UpdateDeploymentConfigurationRequest, replaceRepository: Boolean = false): Boolean = transaction {
        val row = DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq id }.singleOrNull() ?: return@transaction false
        val projectId = row[DeploymentConfigurations.projectId] ?: error("Deployment configuration has no project_id")
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
            createSecretSetVersion(projectId, targetEnvironment, secretsForNewVersion.orEmpty(), "admin")
        } else null
        val readinessType = request.readinessType?.let { it.trim().lowercase().also { type -> require(type in supportedReadinessTypes) { "Readiness type must be docker, http, tcp, or process" } } }
        val readinessTarget = request.readinessTarget?.also { require(it.isNotBlank()) { "Readiness target must not be blank" } }
        request.readinessTimeoutSeconds?.let { require(it in 1..600) { "Readiness timeout must be between 1 and 600 seconds" } }
        request.readinessIntervalSeconds?.let { require(it in 1..30) { "Readiness interval must be between 1 and 30 seconds" } }
        request.readinessProbeTimeoutMillis?.let { require(it in 100..30000) { "Readiness probe timeout must be between 100 and 30000 milliseconds" } }
        DeploymentConfigurations.update({ DeploymentConfigurations.id eq id }) {
            if (replaceRepository) it[repository] = request.repository else request.repository?.let { value -> it[repository] = value }
            request.gitRef?.let { value -> it[gitRef] = value }
            request.registry?.let { value -> it[registry] = value }; request.imageName?.let { value -> it[imageName] = value }
            request.imageTag?.let { value -> it[imageTag] = value }
            request.hostPort?.let { value -> it[hostPort] = value }; request.containerPort?.let { value -> it[containerPort] = value }
            request.network?.let { value -> it[network] = value }; request.restartPolicy?.let { value -> it[restartPolicy] = value }
            envJson?.let { value -> it[DeploymentConfigurations.envJson] = value }; volumesJson?.let { value -> it[DeploymentConfigurations.volumesJson] = value }
            request.createNetworkIfMissing?.let { value -> it[createNetworkIfMissing] = value }
            request.autoDeploy?.let { value -> it[DeploymentConfigurations.autoDeploy] = value }
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
            projectId.let { value -> it[DeploymentConfigurations.projectId] = value }
            it[updatedAt] = LocalDateTime.now()
        }
        // Keep the private worker queue snapshot aligned with the saved configuration.
        DeploymentJobs.update({ DeploymentJobs.id eq id }) {
            if (replaceRepository) it[repository] = request.repository else request.repository?.let { value -> it[repository] = value }
            request.gitRef?.let { value -> it[gitRef] = value }
            request.registry?.let { value -> it[registry] = value }; request.imageName?.let { value -> it[imageName] = value }
            request.imageTag?.let { value -> it[imageTag] = value }
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
            projectId.let { value -> it[DeploymentExecutions.projectId] = value }
            readinessType?.let { value -> it[DeploymentExecutions.readinessType] = value }
            readinessTarget?.let { value -> it[DeploymentExecutions.readinessTarget] = value }
            request.readinessTimeoutSeconds?.let { value -> it[DeploymentExecutions.readinessTimeoutSeconds] = value }
            request.readinessIntervalSeconds?.let { value -> it[DeploymentExecutions.readinessIntervalSeconds] = value }
            request.readinessProbeTimeoutMillis?.let { value -> it[DeploymentExecutions.readinessProbeTimeoutMillis] = value }
        }
        true
    }

    fun redeployConfiguration(id: UUID): UUID? {
        val prepared = transaction {
            val row = DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq id }.singleOrNull() ?: return@transaction null
            val projectId = row[DeploymentConfigurations.projectId] ?: error("Deployment configuration has no project_id")
            val request = com.gatekeeper.deployment.CreateDeploymentRequest(
                repository = row[DeploymentConfigurations.repository],
                gitRef = row[DeploymentConfigurations.gitRef],
                registry = row[DeploymentConfigurations.registry],
                imageName = row[DeploymentConfigurations.imageName],
                imageTag = row[DeploymentConfigurations.imageTag],
                hostPort = row[DeploymentConfigurations.hostPort],
                containerPort = row[DeploymentConfigurations.containerPort],
                network = row[DeploymentConfigurations.network],
                restartPolicy = row[DeploymentConfigurations.restartPolicy],
                projectId = projectId.toString(), autoDeploy = row[DeploymentConfigurations.autoDeploy],
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
            if (secretSet == null && request.secretEnv.isNotEmpty()) {
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

    fun latestForProject(projectId: UUID): DeploymentJobRecord? = transaction {
        DeploymentJobs.selectAll().where { DeploymentJobs.projectId eq projectId }
            .orderBy(DeploymentJobs.createdAt to SortOrder.DESC).limit(1).singleOrNull()
            ?.toRecord()
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

    fun create(request: com.gatekeeper.deployment.CreateDeploymentRequest): UUID = create(request, null)

    private fun create(request: com.gatekeeper.deployment.CreateDeploymentRequest, existingSecretSet: SecretSetReference?): UUID = transaction {
        val id = UUID.randomUUID()
        val projectId = request.projectId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: error("A valid project_id is required")
        check(Projects.selectAll().where { (Projects.id eq projectId) and Projects.deletedAt.isNull() }.count() == 1L) {
            "Project not found"
        }
        val environment = requireEnvironment(request.environment)
        validateReadiness(request.readinessType, request.readinessTarget, request.readinessTimeoutSeconds, request.readinessIntervalSeconds, request.readinessProbeTimeoutMillis)
        val envJson = Json.encodeToString(request.env)
        val secretCiphertext = request.secretEnv.takeIf { it.isNotEmpty() }?.let { values ->
            check(SecretValueCipher.isConfigured()) { "Deployment secret encryption is not configured" }
            SecretValueCipher.encrypt(Json.encodeToString(values))
        }
        val secretSet = existingSecretSet ?: request.secretEnv.takeIf { it.isNotEmpty() }?.let { values ->
            createSecretSetVersion(projectId, environment, values, request.triggerSource)
        }
        val volumesJson = Json.encodeToString(request.volumes)
        // Save editable configuration and immutable execution snapshot before queuing work.
        DeploymentConfigurations.insert {
            it[DeploymentConfigurations.id] = id
            it[repository] = request.repository; it[gitRef] = request.gitRef; it[registry] = request.registry
            it[imageName] = request.imageName; it[imageTag] = request.imageTag
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort; it[network] = request.network
            it[restartPolicy] = request.restartPolicy; it[DeploymentConfigurations.envJson] = envJson
            it[DeploymentConfigurations.secretEnvEncrypted] = secretCiphertext; it[DeploymentConfigurations.volumesJson] = volumesJson
            it[DeploymentConfigurations.secretSetId] = secretSet?.id
            it[DeploymentConfigurations.secretSetVersion] = secretSet?.version
            it[createNetworkIfMissing] = request.createNetworkIfMissing; it[DeploymentConfigurations.autoDeploy] = request.autoDeploy
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
            it[imageName] = request.imageName; it[imageTag] = request.imageTag
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort; it[network] = request.network
            it[restartPolicy] = request.restartPolicy; it[DeploymentExecutions.envJson] = envJson
            it[DeploymentExecutions.secretEnvEncrypted] = secretCiphertext; it[DeploymentExecutions.volumesJson] = volumesJson
            it[DeploymentExecutions.secretSetId] = secretSet?.id
            it[DeploymentExecutions.secretSetVersion] = secretSet?.version
            it[createNetworkIfMissing] = request.createNetworkIfMissing
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
            it[hostPort] = request.hostPort
            it[containerPort] = request.containerPort
            it[network] = request.network
            it[restartPolicy] = request.restartPolicy
            it[DeploymentJobs.envJson] = envJson
            it[DeploymentJobs.secretEnvEncrypted] = secretCiphertext
            it[DeploymentJobs.volumesJson] = volumesJson
            it[DeploymentJobs.createNetworkIfMissing] = request.createNetworkIfMissing
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
            projectId = ownerId.toString(),
            autoDeploy = false,
            hostPort = execution[DeploymentExecutions.hostPort],
            containerPort = execution[DeploymentExecutions.containerPort],
            network = execution[DeploymentExecutions.network],
            restartPolicy = execution[DeploymentExecutions.restartPolicy],
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
            it[imageName] = request.imageName; it[imageTag] = request.imageTag
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort
            it[network] = request.network; it[restartPolicy] = request.restartPolicy
            it[DeploymentConfigurations.envJson] = envJson; it[DeploymentConfigurations.secretEnvEncrypted] = secretCiphertext
            it[DeploymentConfigurations.secretSetId] = secretSet?.id
            it[DeploymentConfigurations.secretSetVersion] = secretSet?.version
            it[DeploymentConfigurations.volumesJson] = volumesJson; it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[DeploymentConfigurations.autoDeploy] = request.autoDeploy; it[DeploymentConfigurations.projectId] = projectId
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
            it[imageName] = request.imageName; it[imageTag] = request.imageTag
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort
            it[network] = request.network; it[restartPolicy] = request.restartPolicy
            it[DeploymentExecutions.envJson] = envJson; it[DeploymentExecutions.secretEnvEncrypted] = secretCiphertext
            it[DeploymentExecutions.secretSetId] = secretSet?.id
            it[DeploymentExecutions.secretSetVersion] = secretSet?.version
            it[DeploymentExecutions.volumesJson] = volumesJson; it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[DeploymentExecutions.projectId] = projectId
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
            it[imageName] = request.imageName; it[imageTag] = request.imageTag
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort
            it[network] = request.network; it[restartPolicy] = request.restartPolicy
            it[DeploymentJobs.envJson] = envJson; it[DeploymentJobs.secretEnvEncrypted] = secretCiphertext
            it[DeploymentJobs.volumesJson] = volumesJson; it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[DeploymentJobs.projectId] = projectId
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

    /** Called immediately before creating a runtime, resolving the immutable secret-set reference. */
    fun resolveSecretEnvForExecution(executionId: UUID): Map<String, String> = transaction {
        resolveSecretEnvForExecutionInTransaction(executionId)
    }

    private fun resolveSecretEnvForExecutionInTransaction(executionId: UUID): Map<String, String> {
        val execution = DeploymentExecutions.selectAll().where { DeploymentExecutions.id eq executionId }.singleOrNull()
        val secretSetId = execution?.get(DeploymentExecutions.secretSetId)
        if (secretSetId != null) {
            val version = execution[DeploymentExecutions.secretSetVersion]
                ?: error("Deployment secret-set version is missing for execution $executionId")
            val setRow = ProjectSecretSetVersions.selectAll().where {
                (ProjectSecretSetVersions.id eq secretSetId) and (ProjectSecretSetVersions.version eq version)
            }.singleOrNull() ?: error("Deployment secret-set version $secretSetId/$version is unavailable")
            check(setRow[ProjectSecretSetVersions.projectId] == execution[DeploymentExecutions.projectId]) {
                "Deployment secret-set project does not match execution $executionId"
            }
            check(setRow[ProjectSecretSetVersions.environment] == (execution[DeploymentExecutions.environment] ?: "production")) {
                "Deployment secret-set environment does not match execution $executionId"
            }
            return Json.decodeFromString(SecretValueCipher.decrypt(setRow[ProjectSecretSetVersions.encryptedPayload]))
        }
        val encrypted = execution?.get(DeploymentExecutions.secretEnvEncrypted)
            ?: DeploymentJobs.selectAll().where { DeploymentJobs.id eq executionId }.singleOrNull()?.get(DeploymentJobs.secretEnvEncrypted)
        return encrypted?.let { Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(it)) }.orEmpty()
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
        val isReadyForCutover = row[DeploymentJobs.currentStep] in setOf("readiness_succeeded", "cutover_in_progress")
        DeploymentJobs.update({ DeploymentJobs.id eq row[DeploymentJobs.id] }) {
            it[status] = "running"; it[currentStep] = if (isReadyForCutover) "cutover_in_progress" else "building"; it[startedAt] = now; it[updatedAt] = now
        }
        DeploymentExecutions.update({ DeploymentExecutions.id eq row[DeploymentJobs.id] }) {
            it[status] = "running"; it[currentStep] = if (isReadyForCutover) "cutover_in_progress" else "building"; it[startedAt] = now; it[updatedAt] = now
        }
        if (!isReadyForCutover) DeploymentApplicationService.transition(row[DeploymentJobs.id], DeploymentStatus.BUILDING)
        DeploymentJobs.selectAll().where { DeploymentJobs.id eq row[DeploymentJobs.id] }
            .singleOrNull()?.toRecord(includeSecretEnv = false)
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
        // Use the private queue snapshot for log/error redaction while the worker processes this job.
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

    private fun ResultRow.toRecord(includeSecretEnv: Boolean = true): DeploymentJobRecord {
        val id = this[DeploymentJobs.id]
        val execution = DeploymentExecutions.selectAll().where { DeploymentExecutions.id eq id }.singleOrNull()
        return DeploymentJobRecord(
        this[DeploymentJobs.id], this[DeploymentJobs.repository], this[DeploymentJobs.gitRef], this[DeploymentJobs.registry],
        this[DeploymentJobs.imageName], this[DeploymentJobs.imageTag], this[DeploymentJobs.hostPort], this[DeploymentJobs.containerPort], this[DeploymentJobs.network], this[DeploymentJobs.restartPolicy], runCatching { Json.decodeFromString<Map<String, String>>(this[DeploymentJobs.envJson]) }.getOrDefault(emptyMap()), if (includeSecretEnv) this[DeploymentJobs.secretEnvEncrypted]?.let { Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(it)) }.orEmpty() else emptyMap(), runCatching { Json.decodeFromString<List<VolumeMount>>(this[DeploymentJobs.volumesJson]) }.getOrDefault(emptyList()), this[DeploymentJobs.createNetworkIfMissing], this[DeploymentJobs.status], this[DeploymentJobs.currentStep],
        this[DeploymentJobs.logs], this[DeploymentJobs.commitSha], this[DeploymentJobs.imageDigest], this[DeploymentJobs.errorMessage],
        this[DeploymentJobs.createdAt], this[DeploymentJobs.startedAt], this[DeploymentJobs.completedAt], this[DeploymentJobs.updatedAt], this[DeploymentJobs.projectId], this[DeploymentJobs.triggerSource], this[DeploymentJobs.environment],
        this[DeploymentJobs.readinessType], this[DeploymentJobs.readinessTarget], this[DeploymentJobs.readinessTimeoutSeconds], this[DeploymentJobs.readinessIntervalSeconds], this[DeploymentJobs.readinessProbeTimeoutMillis],
        execution?.get(DeploymentExecutions.secretSetId), execution?.get(DeploymentExecutions.secretSetVersion)
        )
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

}
