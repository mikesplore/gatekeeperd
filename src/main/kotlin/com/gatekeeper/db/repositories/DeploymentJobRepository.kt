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
import com.gatekeeper.db.tables.Services
import com.gatekeeper.db.tables.ProjectSharedEnvironmentVersions

private data class SecretSetReference(val id: UUID, val version: Int)
private data class SharedEnvironmentSetReference(val id: UUID, val version: Int)

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
    val secretSetVersion: Int? = null,
    val serviceId: UUID
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

    fun registryCredentialForDeployment(id: UUID): RegistryCredentialRecord? = transaction {
        val credentialId = DeploymentExecutions.selectAll().where { DeploymentExecutions.id eq id }
            .singleOrNull()?.get(DeploymentExecutions.registryCredentialId)
        val job = DeploymentJobs.selectAll().where { DeploymentJobs.id eq id }.singleOrNull() ?: return@transaction null
        val registry = job[DeploymentJobs.registry]
        if (credentialId != null) {
            val selected = RegistryCredentialRepository.findByCredentialId(credentialId)
                ?: error("Selected registry credential no longer exists")
            require(selected.registry == registry) { "Selected registry credential does not match deployment registry" }
            selected
        } else RegistryCredentialRepository.find(registry)
    }

    private fun requireRegistryCredential(id: UUID, registry: String) {
        val metadata = ProviderCredentialRepository.findMetadata(id)
            ?: error("Selected registry credential does not exist")
        require(metadata.provider == "docker" && metadata.credentialType == "registry") {
            "Selected provider credential is not a registry credential"
        }
        require(metadata.current) { "Selected registry credential has been retired" }
        require(metadata.scope == registry) { "Selected registry credential does not match the configured registry" }
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
        val secretSetVersion: Int?,
        val registryCredentialId: UUID?
    )

    fun configurationIdForProject(projectId: UUID, environment: String = "production"): UUID? = transaction {
        val defaultServiceId = defaultServiceId(projectId)
        DeploymentConfigurations.selectAll().where {
            (DeploymentConfigurations.projectId eq projectId) and
                    ((DeploymentConfigurations.serviceId eq defaultServiceId) or DeploymentConfigurations.serviceId.isNull())
        }.toList()
            .filter { (it[DeploymentConfigurations.environment] ?: "production") == environment }
            .maxByOrNull { it[DeploymentConfigurations.serviceId] == defaultServiceId }?.get(DeploymentConfigurations.id)
    }

    fun configurationIdForService(projectId: UUID, serviceId: UUID, environment: String = "production"): UUID? = transaction {
        DeploymentConfigurations.selectAll().where {
            (DeploymentConfigurations.projectId eq projectId) and (DeploymentConfigurations.serviceId eq serviceId)
        }.toList().firstOrNull { it[DeploymentConfigurations.environment] == environment }
            ?.get(DeploymentConfigurations.id)
    }

    data class EnvironmentDeploymentResult(val setId: UUID, val version: Int, val deploymentIds: List<UUID>)

    /** Saves one service's environment version and queues its deployment atomically. */
    fun updateServiceEnvironmentAndDeploy(
        projectId: UUID,
        serviceId: UUID,
        environment: String,
        values: Map<String, String>,
        actor: String,
        registry: String? = null,
        sharedEnvironmentSetId: UUID? = null,
        sharedEnvironmentSetVersion: Int? = null
    ): EnvironmentDeploymentResult? = transaction {
        requireEnvironment(environment)
        val row = DeploymentConfigurations.selectAll().where {
            (DeploymentConfigurations.projectId eq projectId) and (DeploymentConfigurations.serviceId eq serviceId)
        }.toList().firstOrNull { (it[DeploymentConfigurations.environment] ?: "production") == environment }
        if (row == null) {
            check(sharedEnvironmentSetId == null) {
                "Configure the service runtime before importing shared environment variables"
            }
            val serviceSet = createSecretSetVersion(projectId, environment, values, actor, serviceId)
            return@transaction EnvironmentDeploymentResult(serviceSet.id, serviceSet.version, emptyList())
        }
        check(Services.selectAll().where { (Services.id eq serviceId) and (Services.projectId eq projectId) }.count() == 1L) {
            "Service does not belong to project"
        }
        val sharedSet = sharedEnvironmentSet(
            projectId, environment, sharedEnvironmentSetId, sharedEnvironmentSetVersion
        )
        val serviceSet = createSecretSetVersion(projectId, environment, values, actor, serviceId)
        DeploymentConfigurations.update({ DeploymentConfigurations.id eq row[DeploymentConfigurations.id] }) {
            it[DeploymentConfigurations.serviceId] = serviceId
            it[DeploymentConfigurations.registry] = registry ?: row[DeploymentConfigurations.registry]
            it[DeploymentConfigurations.secretSetId] = serviceSet.id
            it[DeploymentConfigurations.secretSetVersion] = serviceSet.version
            it[DeploymentConfigurations.sharedEnvironmentSetId] = sharedSet?.id
            it[DeploymentConfigurations.sharedEnvironmentSetVersion] = sharedSet?.version
            it[DeploymentConfigurations.envJson] = "{}"
            it[DeploymentConfigurations.secretEnvEncrypted] = null
            registry?.let { value -> it[DeploymentConfigurations.registry] = value }
            it[DeploymentConfigurations.updatedAt] = LocalDateTime.now()
        }
        DeploymentJobs.update({ DeploymentJobs.id eq row[DeploymentConfigurations.id] }) {
            it[DeploymentJobs.registry] = registry ?: row[DeploymentConfigurations.registry]
            it[DeploymentJobs.updatedAt] = LocalDateTime.now()
        }
        val updated = DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq row[DeploymentConfigurations.id] }.single()
        val request = requestFromConfiguration(updated, "service_environment_update", serviceId)
        val deploymentId = create(request, serviceSet)
        EnvironmentDeploymentResult(serviceSet.id, serviceSet.version, listOf(deploymentId))
    }

    /** Writes a shared version and explicitly refreshes consumers pinned to the prior latest version. */
    fun updateSharedEnvironmentAndDeploy(
        projectId: UUID,
        environment: String,
        values: Map<String, String>,
        actor: String
    ): EnvironmentDeploymentResult = transaction {
        requireEnvironment(environment)
        val previousLatest = ProjectSharedEnvironmentVersions.selectAll().where {
            (ProjectSharedEnvironmentVersions.projectId eq projectId) and
                (ProjectSharedEnvironmentVersions.environment eq environment)
        }.maxByOrNull { it[ProjectSharedEnvironmentVersions.version] }
        val previousId = previousLatest?.get(ProjectSharedEnvironmentVersions.id)
        val previousVersion = previousLatest?.get(ProjectSharedEnvironmentVersions.version)
        val (sharedId, sharedVersion) = createProjectSharedEnvironmentVersion(projectId, environment, values, actor)
        val pinnedConsumers = if (previousId == null || previousVersion == null) emptyList() else {
            DeploymentConfigurations.selectAll().where {
                (DeploymentConfigurations.projectId eq projectId) and
                    (DeploymentConfigurations.sharedEnvironmentSetId eq previousId)
            }.toList().filter {
                it[DeploymentConfigurations.environment] == environment &&
                    it[DeploymentConfigurations.sharedEnvironmentSetVersion] == previousVersion
            }
        }
        val deployments = pinnedConsumers.map { original ->
            val configId = original[DeploymentConfigurations.id]
            val serviceId = original[DeploymentConfigurations.serviceId] ?: defaultServiceId(projectId)
            DeploymentConfigurations.update({ DeploymentConfigurations.id eq configId }) {
                it[DeploymentConfigurations.serviceId] = serviceId
                it[DeploymentConfigurations.sharedEnvironmentSetId] = sharedId
                it[DeploymentConfigurations.sharedEnvironmentSetVersion] = sharedVersion
                it[DeploymentConfigurations.updatedAt] = LocalDateTime.now()
            }
            val updated = DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq configId }.single()
            val serviceSet = updated[DeploymentConfigurations.secretSetId]?.let { id ->
                SecretSetReference(id, updated[DeploymentConfigurations.secretSetVersion] ?: error("Service environment version is missing"))
            }
            create(requestFromConfiguration(updated, "shared_environment_update", serviceId), serviceSet)
        }
        EnvironmentDeploymentResult(sharedId, sharedVersion, deployments)
    }

    private fun requestFromConfiguration(row: ResultRow, trigger: String, serviceId: UUID) =
        com.gatekeeper.deployment.CreateDeploymentRequest(
            repository = row[DeploymentConfigurations.repository], gitRef = row[DeploymentConfigurations.gitRef],
            registry = row[DeploymentConfigurations.registry], imageName = row[DeploymentConfigurations.imageName],
            imageTag = row[DeploymentConfigurations.imageTag], hostPort = row[DeploymentConfigurations.hostPort],
            containerPort = row[DeploymentConfigurations.containerPort], network = row[DeploymentConfigurations.network],
            restartPolicy = row[DeploymentConfigurations.restartPolicy], projectId = row[DeploymentConfigurations.projectId].toString(),
            serviceId = serviceId.toString(), autoDeploy = row[DeploymentConfigurations.autoDeploy], triggerSource = trigger,
            sharedEnvironmentSetId = row[DeploymentConfigurations.sharedEnvironmentSetId]?.toString(),
            sharedEnvironmentSetVersion = row[DeploymentConfigurations.sharedEnvironmentSetVersion],
            volumes = Json.decodeFromString(row[DeploymentConfigurations.volumesJson]),
            createNetworkIfMissing = row[DeploymentConfigurations.createNetworkIfMissing],
            environment = row[DeploymentConfigurations.environment] ?: "production",
            readinessType = row[DeploymentConfigurations.readinessType], readinessTarget = row[DeploymentConfigurations.readinessTarget],
            readinessTimeoutSeconds = row[DeploymentConfigurations.readinessTimeoutSeconds],
            readinessIntervalSeconds = row[DeploymentConfigurations.readinessIntervalSeconds],
            readinessProbeTimeoutMillis = row[DeploymentConfigurations.readinessProbeTimeoutMillis]
        )

    private fun defaultServiceId(projectId: UUID): UUID = Services.selectAll().where {
        (Services.projectId eq projectId) and (Services.isDefault eq true)
    }.singleOrNull()?.get(Services.id) ?: error("Default service not found for project $projectId")

    fun defaultServiceIdForProject(projectId: UUID): UUID = transaction { defaultServiceId(projectId) }

    fun createProjectSharedEnvironmentVersion(
        projectId: UUID,
        environment: String,
        values: Map<String, String>,
        createdBy: String
    ): Pair<UUID, Int> = transaction {
        requireEnvironment(environment)
        check(SecretValueCipher.isConfigured()) { "Deployment secret encryption is not configured" }
        val nextVersion = (ProjectSharedEnvironmentVersions.selectAll().where {
            (ProjectSharedEnvironmentVersions.projectId eq projectId) and
                    (ProjectSharedEnvironmentVersions.environment eq environment)
        }.maxOfOrNull { it[ProjectSharedEnvironmentVersions.version] } ?: 0) + 1
        val id = UUID.randomUUID()
        ProjectSharedEnvironmentVersions.insert {
            it[ProjectSharedEnvironmentVersions.id] = id
            it[ProjectSharedEnvironmentVersions.projectId] = projectId
            it[ProjectSharedEnvironmentVersions.environment] = environment
            it[ProjectSharedEnvironmentVersions.version] = nextVersion
            it[ProjectSharedEnvironmentVersions.encryptedPayload] = SecretValueCipher.encrypt(Json.encodeToString(values))
            it[ProjectSharedEnvironmentVersions.createdAt] = LocalDateTime.now()
            it[ProjectSharedEnvironmentVersions.createdBy] = createdBy
        }
        id to nextVersion
    }

    private fun sharedEnvironmentSet(
        projectId: UUID,
        environment: String,
        id: UUID?,
        version: Int?
    ): SharedEnvironmentSetReference? {
        if (id == null && version == null) return null
        require(id != null && version != null && version > 0) { "Shared environment set ID and version must be supplied together" }
        val row = ProjectSharedEnvironmentVersions.selectAll().where {
            (ProjectSharedEnvironmentVersions.id eq id) and
                (ProjectSharedEnvironmentVersions.version eq version)
        }.singleOrNull() ?: error("Shared environment set $id/$version not found")
        check(row[ProjectSharedEnvironmentVersions.projectId] == projectId) { "Shared environment set belongs to a different project" }
        check(row[ProjectSharedEnvironmentVersions.environment] == environment) { "Shared environment set belongs to a different environment" }
        return SharedEnvironmentSetReference(id, version)
    }

    private fun sharedEnvironmentValues(reference: SharedEnvironmentSetReference?): Map<String, String> {
        if (reference == null) return emptyMap()
        val row = ProjectSharedEnvironmentVersions.selectAll().where {
            (ProjectSharedEnvironmentVersions.id eq reference.id) and
                (ProjectSharedEnvironmentVersions.version eq reference.version)
        }.singleOrNull() ?: error("Shared environment set ${reference.id}/${reference.version} not found")
        return Json.decodeFromString(SecretValueCipher.decrypt(row[ProjectSharedEnvironmentVersions.encryptedPayload]))
    }

    private fun serviceEnvironmentValues(reference: SecretSetReference?): Map<String, String> {
        if (reference == null) return emptyMap()
        val row = ProjectSecretSetVersions.selectAll().where {
            (ProjectSecretSetVersions.id eq reference.id) and
                (ProjectSecretSetVersions.version eq reference.version)
        }.singleOrNull() ?: error("Service environment set ${reference.id}/${reference.version} not found")
        return Json.decodeFromString(SecretValueCipher.decrypt(row[ProjectSecretSetVersions.encryptedPayload]))
    }

    fun configurationSummary(projectId: UUID, environment: String = "production"): ConfigurationSummary? = transaction {
        val id = configurationIdForProject(projectId, environment) ?: return@transaction null
        configurationSummaryById(id)
    }

    fun configurationSummaryForService(projectId: UUID, serviceId: UUID, environment: String = "production"): ConfigurationSummary? = transaction {
        val id = configurationIdForService(projectId, serviceId, environment) ?: return@transaction null
        configurationSummaryById(id)
    }

    fun configurationSummaryById(id: UUID): ConfigurationSummary? = transaction {
        val row = DeploymentConfigurations.selectAll().where { DeploymentConfigurations.id eq id }.singleOrNull()
            ?: return@transaction null
        val serviceSet = row[DeploymentConfigurations.secretSetId]?.let { setId ->
            SecretSetReference(setId, row[DeploymentConfigurations.secretSetVersion] ?: error("Service environment version is missing"))
        }
        val sharedSet = row[DeploymentConfigurations.sharedEnvironmentSetId]?.let { setId ->
            SharedEnvironmentSetReference(setId, row[DeploymentConfigurations.sharedEnvironmentSetVersion] ?: error("Shared environment version is missing"))
        }
        val envKeys = (serviceEnvironmentValues(serviceSet).keys + sharedEnvironmentValues(sharedSet).keys).distinct().sorted()
        ConfigurationSummary(
            id, row[DeploymentConfigurations.repository], row[DeploymentConfigurations.gitRef], row[DeploymentConfigurations.registry],
            row[DeploymentConfigurations.imageName], row[DeploymentConfigurations.imageTag], row[DeploymentConfigurations.autoDeploy], row[DeploymentConfigurations.containerPort],
            row[DeploymentConfigurations.hostPort], row[DeploymentConfigurations.network], row[DeploymentConfigurations.restartPolicy],
            row[DeploymentConfigurations.environment], emptyMap(), envKeys,
            row[DeploymentConfigurations.secretSetId], row[DeploymentConfigurations.secretSetVersion],
            row[DeploymentConfigurations.registryCredentialId]
        )
    }

    fun upsertProjectConfiguration(projectId: UUID, request: com.gatekeeper.deployment.CreateDeploymentRequest): UUID {
        val serviceId = request.serviceId?.let(UUID::fromString) ?: defaultServiceIdForProject(projectId)
        val currentId = configurationIdForService(projectId, serviceId, request.environment)
            ?: return createConfiguration(projectId, request)
        val updated = updateConfiguration(currentId, UpdateDeploymentConfigurationRequest(
            repository = request.repository, gitRef = request.gitRef, registry = request.registry,
            registryCredentialId = request.registryCredentialId,
            imageName = request.imageName, imageTag = request.imageTag,
            hostPort = request.hostPort, containerPort = request.containerPort, network = request.network,
            restartPolicy = request.restartPolicy, env = request.env, volumes = request.volumes,
            sharedEnvironmentSetId = request.sharedEnvironmentSetId,
            sharedEnvironmentSetVersion = request.sharedEnvironmentSetVersion,
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
        val serviceId = row[DeploymentConfigurations.serviceId] ?: defaultServiceId(projectId)
        val secretSet = createSecretSetVersion(projectId, environment, secretEnv, actor, serviceId)
        val sharedSet = row[DeploymentConfigurations.sharedEnvironmentSetId]?.let { sharedId ->
            SharedEnvironmentSetReference(
                sharedId,
                row[DeploymentConfigurations.sharedEnvironmentSetVersion] ?: error("Shared environment version is missing")
            )
        }
        DeploymentConfigurations.update({ DeploymentConfigurations.id eq configurationId }) {
            it[DeploymentConfigurations.serviceId] = serviceId
            it[DeploymentConfigurations.secretSetId] = secretSet.id
            it[DeploymentConfigurations.secretSetVersion] = secretSet.version
            it[DeploymentConfigurations.envJson] = "{}"
            it[DeploymentConfigurations.secretEnvEncrypted] = null
            it[DeploymentConfigurations.updatedAt] = LocalDateTime.now()
        }
        DeploymentJobs.update({ DeploymentJobs.id eq configurationId }) {
            it[DeploymentJobs.serviceId] = serviceId
            it[DeploymentJobs.envJson] = "{}"
            it[DeploymentJobs.secretEnvEncrypted] = null
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
                env = emptyMap(), secretEnv = emptyMap(),
                sharedEnvironmentSetId = sharedSet?.id?.toString(), sharedEnvironmentSetVersion = sharedSet?.version,
                volumes = Json.decodeFromString(saved[DeploymentConfigurations.volumesJson]),
                createNetworkIfMissing = saved[DeploymentConfigurations.createNetworkIfMissing], environment = environment,
                readinessType = saved[DeploymentConfigurations.readinessType], readinessTarget = saved[DeploymentConfigurations.readinessTarget],
                readinessTimeoutSeconds = saved[DeploymentConfigurations.readinessTimeoutSeconds],
                readinessIntervalSeconds = saved[DeploymentConfigurations.readinessIntervalSeconds],
                readinessProbeTimeoutMillis = saved[DeploymentConfigurations.readinessProbeTimeoutMillis]
            )
        create(request, secretSet)
    }

    fun createConfiguration(projectId: UUID, request: com.gatekeeper.deployment.CreateDeploymentRequest): UUID = transaction {
        val project = Projects.selectAll().where { (Projects.id eq projectId) and Projects.deletedAt.isNull() }.singleOrNull()
            ?: error("Project not found")
        val environment = requireEnvironment(request.environment)
        val requestedServiceId = request.serviceId?.let(UUID::fromString)
        val serviceId = requestedServiceId ?: defaultServiceId(projectId)
        check(Services.selectAll().where { (Services.id eq serviceId) and (Services.projectId eq projectId) }.count() == 1L) {
            "Service does not belong to project"
        }
        validateReadiness(request.readinessType, request.readinessTarget, request.readinessTimeoutSeconds, request.readinessIntervalSeconds, request.readinessProbeTimeoutMillis)
        check(DeploymentConfigurations.selectAll().where {
            (DeploymentConfigurations.projectId eq projectId) and (DeploymentConfigurations.serviceId eq serviceId)
        }.none { (it[DeploymentConfigurations.environment] ?: "production") == environment }) {
            "Service already has a deployment configuration for this environment"
        }
        val id = UUID.randomUUID()
        val sharedSet = sharedEnvironmentSet(
            projectId, environment, request.sharedEnvironmentSetId?.let(UUID::fromString), request.sharedEnvironmentSetVersion
        )
        val serviceEnvironment = request.env + request.secretEnv
        val secretSet = serviceEnvironment.takeIf { it.isNotEmpty() }?.let { values ->
            createSecretSetVersion(projectId, environment, values, "admin", serviceId)
        } ?: latestServiceSecretSet(projectId, serviceId, environment)
        DeploymentConfigurations.insert {
            it[DeploymentConfigurations.id] = id
            it[repository] = request.repository; it[gitRef] = request.gitRef; it[registry] = request.registry
            val registryCredentialId = request.registryCredentialId?.takeIf(String::isNotBlank)?.let(UUID::fromString)
            registryCredentialId?.let { requireRegistryCredential(it, request.registry) }
            it[DeploymentConfigurations.registryCredentialId] = registryCredentialId
            it[imageName] = request.imageName; it[imageTag] = request.imageTag
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort; it[network] = request.network
            it[restartPolicy] = request.restartPolicy; it[DeploymentConfigurations.envJson] = "{}"
            it[DeploymentConfigurations.secretEnvEncrypted] = null
            it[DeploymentConfigurations.secretSetId] = secretSet?.id
            it[DeploymentConfigurations.secretSetVersion] = secretSet?.version
            it[DeploymentConfigurations.sharedEnvironmentSetId] = sharedSet?.id
            it[DeploymentConfigurations.sharedEnvironmentSetVersion] = sharedSet?.version
            it[DeploymentConfigurations.volumesJson] = Json.encodeToString(request.volumes)
            it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[DeploymentConfigurations.autoDeploy] = request.autoDeploy
            it[DeploymentConfigurations.projectId] = projectId
            it[DeploymentConfigurations.serviceId] = serviceId
            it[DeploymentConfigurations.environment] = environment
            it[DeploymentConfigurations.readinessType] = request.readinessType?.lowercase()
            it[DeploymentConfigurations.readinessTarget] = request.readinessTarget
            it[DeploymentConfigurations.readinessTimeoutSeconds] = request.readinessTimeoutSeconds
            it[DeploymentConfigurations.readinessIntervalSeconds] = request.readinessIntervalSeconds
            it[DeploymentConfigurations.readinessProbeTimeoutMillis] = request.readinessProbeTimeoutMillis
        }
        id
    }

    data class AdoptedRuntimeRecord(val deploymentId: UUID, val configurationId: UUID, val serviceId: UUID)

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
        val serviceId = defaultServiceId(projectId)
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
        DeploymentConfigurations.update({ DeploymentConfigurations.id eq configurationId }) {
            it[DeploymentConfigurations.serviceId] = serviceId
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
            it[DeploymentConfigurations.secretEnvEncrypted] = null
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
            it[DeploymentExecutions.registryCredentialId] = DeploymentConfigurations.selectAll()
                .where { DeploymentConfigurations.id eq configurationId }.single()[DeploymentConfigurations.registryCredentialId]
            it[DeploymentExecutions.imageName] = imageName
            it[DeploymentExecutions.imageTag] = imageTag
            it[DeploymentExecutions.hostPort] = null
            it[DeploymentExecutions.containerPort] = containerPort
            it[DeploymentExecutions.network] = container.networks.firstOrNull() ?: "bridge"
            it[DeploymentExecutions.restartPolicy] = container.restartPolicy
            it[DeploymentExecutions.envJson] = "{}"
            it[DeploymentExecutions.secretEnvEncrypted] = null
            it[DeploymentExecutions.secretSetId] = secretSet?.id
            it[DeploymentExecutions.secretSetVersion] = secretSet?.version
            it[DeploymentExecutions.resolvedEnvironmentSourcesJson] = Json.encodeToString(container.environment.keys.associateWith { "service" })
            it[DeploymentExecutions.volumesJson] = Json.encodeToString(container.volumes)
            it[DeploymentExecutions.createNetworkIfMissing] = false
            it[DeploymentExecutions.projectId] = projectId
            it[DeploymentExecutions.serviceId] = serviceId
            it[DeploymentExecutions.environment] = environment
            it[DeploymentExecutions.readinessType] = "tcp"
            it[DeploymentExecutions.readinessTarget] = containerPort.toString()
            it[DeploymentExecutions.readinessTimeoutSeconds] = 1
            it[DeploymentExecutions.readinessIntervalSeconds] = 1
            it[DeploymentExecutions.readinessProbeTimeoutMillis] = 1000
            it[DeploymentExecutions.registryCredentialId] = DeploymentConfigurations.selectAll()
                .where { DeploymentConfigurations.id eq configurationId }.single()[DeploymentConfigurations.registryCredentialId]
            it[DeploymentExecutions.triggerSource] = "container_adoption"
            it[DeploymentExecutions.status] = "succeeded"
            it[DeploymentExecutions.currentStep] = "readiness_succeeded"
            it[DeploymentExecutions.logs] = "Existing running container adopted; environment captured as encrypted secret-set version."
            it[DeploymentExecutions.imageDigest] = container.imageDigest
            it[DeploymentExecutions.completedAt] = LocalDateTime.now()
        }
        val mappedHostPort = container.ports[containerPort] ?: error("Selected container port is not published")
        check(DeploymentApplicationService.recordAdoptedRuntime(
            id = id, projectId = projectId, serviceId = serviceId, configurationId = configurationId, executionId = execution[DeploymentExecutions.id],
            environment = environment, triggerSource = "container_adoption", containerName = container.name,
            hostPort = mappedHostPort, containerPort = containerPort, portMappings = container.ports,
            imageDigest = container.imageDigest, secretSetId = secretSet?.id, secretSetVersion = secretSet?.version
        )) { "Unable to record adopted runtime" }
        AdoptedRuntimeRecord(id, configurationId, serviceId)
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
        val projectId = row[DeploymentConfigurations.projectId]
        val volumesJson = request.volumes?.let(Json::encodeToString)
        val environment = request.environment?.let(::requireEnvironment)
        val currentEnvironment = row[DeploymentConfigurations.environment] ?: "production"
        val targetEnvironment = environment ?: currentEnvironment
        val environmentChanged = targetEnvironment != currentEnvironment
        val currentServiceRef = row[DeploymentConfigurations.secretSetId]?.let { setId ->
            SecretSetReference(setId, row[DeploymentConfigurations.secretSetVersion] ?: error("Service environment version is missing"))
        }
        val currentServiceValues = if (currentServiceRef != null) serviceEnvironmentValues(currentServiceRef) else {
            val legacySecrets = row[DeploymentConfigurations.secretEnvEncrypted]?.let {
                Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(it))
            }.orEmpty()
            Json.decodeFromString<Map<String, String>>(row[DeploymentConfigurations.envJson]) + legacySecrets
        }
        val environmentValuesChanged = request.env != null || request.secretEnv != null || environmentChanged
        val newServiceValues = when {
            request.env != null -> request.env + (request.secretEnv ?: emptyMap())
            request.secretEnv != null -> currentServiceValues + request.secretEnv
            else -> currentServiceValues
        }
        val serviceId = row[DeploymentConfigurations.serviceId] ?: defaultServiceId(projectId)
        val newSecretSet = if (environmentValuesChanged) {
            createSecretSetVersion(projectId, targetEnvironment, newServiceValues, "admin", serviceId)
        } else null
        val sharedSet = if (request.sharedEnvironmentSetId != null || request.sharedEnvironmentSetVersion != null) {
            sharedEnvironmentSet(projectId, targetEnvironment, request.sharedEnvironmentSetId?.let(UUID::fromString), request.sharedEnvironmentSetVersion)
        } else if (environmentChanged) null else row[DeploymentConfigurations.sharedEnvironmentSetId]?.let { sharedId ->
            SharedEnvironmentSetReference(sharedId, row[DeploymentConfigurations.sharedEnvironmentSetVersion] ?: error("Shared environment version is missing"))
        }
        val readinessType = request.readinessType?.let { it.trim().lowercase().also { type -> require(type in supportedReadinessTypes) { "Readiness type must be docker, http, tcp, or process" } } }
        val registryCredentialId = request.registryCredentialId?.takeIf(String::isNotBlank)?.let(UUID::fromString)
        if (registryCredentialId != null) requireRegistryCredential(registryCredentialId, request.registry ?: row[DeploymentConfigurations.registry])
        val readinessTarget = request.readinessTarget?.also { require(it.isNotBlank()) { "Readiness target must not be blank" } }
        request.readinessTimeoutSeconds?.let { require(it in 1..600) { "Readiness timeout must be between 1 and 600 seconds" } }
        request.readinessIntervalSeconds?.let { require(it in 1..30) { "Readiness interval must be between 1 and 30 seconds" } }
        request.readinessProbeTimeoutMillis?.let { require(it in 100..30000) { "Readiness probe timeout must be between 100 and 30000 milliseconds" } }
        DeploymentConfigurations.update({ DeploymentConfigurations.id eq id }) {
            if (replaceRepository) it[repository] = request.repository else request.repository?.let { value -> it[repository] = value }
            request.gitRef?.let { value -> it[gitRef] = value }
            request.registry?.let { value -> it[registry] = value }; request.imageName?.let { value -> it[imageName] = value }
            if (request.registryCredentialId != null) it[DeploymentConfigurations.registryCredentialId] = registryCredentialId
            request.imageTag?.let { value -> it[imageTag] = value }
            request.hostPort?.let { value -> it[hostPort] = value }; request.containerPort?.let { value -> it[containerPort] = value }
            request.network?.let { value -> it[network] = value }; request.restartPolicy?.let { value -> it[restartPolicy] = value }
            if (environmentValuesChanged) {
                it[DeploymentConfigurations.envJson] = "{}"
                it[DeploymentConfigurations.secretEnvEncrypted] = null
                it[DeploymentConfigurations.secretSetId] = newSecretSet?.id
                it[DeploymentConfigurations.secretSetVersion] = newSecretSet?.version
            }
            volumesJson?.let { value -> it[DeploymentConfigurations.volumesJson] = value }
            request.createNetworkIfMissing?.let { value -> it[createNetworkIfMissing] = value }
            request.autoDeploy?.let { value -> it[DeploymentConfigurations.autoDeploy] = value }
            it[DeploymentConfigurations.sharedEnvironmentSetId] = sharedSet?.id
            it[DeploymentConfigurations.sharedEnvironmentSetVersion] = sharedSet?.version
            environment?.let { value -> it[DeploymentConfigurations.environment] = value }
            readinessType?.let { value -> it[DeploymentConfigurations.readinessType] = value }
            readinessTarget?.let { value -> it[DeploymentConfigurations.readinessTarget] = value }
            request.readinessTimeoutSeconds?.let { value -> it[DeploymentConfigurations.readinessTimeoutSeconds] = value }
            request.readinessIntervalSeconds?.let { value -> it[DeploymentConfigurations.readinessIntervalSeconds] = value }
            request.readinessProbeTimeoutMillis?.let { value -> it[DeploymentConfigurations.readinessProbeTimeoutMillis] = value }
            projectId.let { value -> it[DeploymentConfigurations.projectId] = value }
            it[DeploymentConfigurations.serviceId] = serviceId
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
            if (environmentValuesChanged) {
                it[DeploymentJobs.envJson] = "{}"
                it[DeploymentJobs.secretEnvEncrypted] = null
            }
            volumesJson?.let { value -> it[DeploymentJobs.volumesJson] = value }
            request.createNetworkIfMissing?.let { value -> it[createNetworkIfMissing] = value }
            environment?.let { value -> it[DeploymentJobs.environment] = value }
            readinessType?.let { value -> it[DeploymentJobs.readinessType] = value }
            readinessTarget?.let { value -> it[DeploymentJobs.readinessTarget] = value }
            request.readinessTimeoutSeconds?.let { value -> it[DeploymentJobs.readinessTimeoutSeconds] = value }
            request.readinessIntervalSeconds?.let { value -> it[DeploymentJobs.readinessIntervalSeconds] = value }
            request.readinessProbeTimeoutMillis?.let { value -> it[DeploymentJobs.readinessProbeTimeoutMillis] = value }
            projectId?.let { value -> it[DeploymentJobs.projectId] = value }
            it[DeploymentJobs.serviceId] = serviceId
            it[updatedAt] = LocalDateTime.now()
        }
        DeploymentExecutions.update({ DeploymentExecutions.id eq id }) {
            projectId.let { value -> it[DeploymentExecutions.projectId] = value }
            it[DeploymentExecutions.serviceId] = serviceId
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
            var serviceSet = row[DeploymentConfigurations.secretSetId]?.let { setId ->
                row[DeploymentConfigurations.secretSetVersion]?.let { version -> SecretSetReference(setId, version) }
            }
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
                serviceId = row[DeploymentConfigurations.serviceId]?.toString(),
                triggerSource = "manual_redeploy",
                env = if (serviceSet == null) runCatching { Json.decodeFromString<Map<String, String>>(row[DeploymentConfigurations.envJson]) }.getOrDefault(emptyMap()) else emptyMap(),
                secretEnv = if (serviceSet == null) row[DeploymentConfigurations.secretEnvEncrypted]?.let { encrypted ->
                    Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(encrypted))
                }.orEmpty() else emptyMap(),
                sharedEnvironmentSetId = row[DeploymentConfigurations.sharedEnvironmentSetId]?.toString(),
                sharedEnvironmentSetVersion = row[DeploymentConfigurations.sharedEnvironmentSetVersion],
                volumes = runCatching { Json.decodeFromString<List<VolumeMount>>(row[DeploymentConfigurations.volumesJson]) }.getOrDefault(emptyList()),
                createNetworkIfMissing = row[DeploymentConfigurations.createNetworkIfMissing],
                environment = row[DeploymentConfigurations.environment] ?: "production",
                readinessType = row[DeploymentConfigurations.readinessType],
                readinessTarget = row[DeploymentConfigurations.readinessTarget],
                readinessTimeoutSeconds = row[DeploymentConfigurations.readinessTimeoutSeconds],
                readinessIntervalSeconds = row[DeploymentConfigurations.readinessIntervalSeconds],
                readinessProbeTimeoutMillis = row[DeploymentConfigurations.readinessProbeTimeoutMillis]
            )
            if (serviceSet == null && (request.env.isNotEmpty() || request.secretEnv.isNotEmpty())) {
                val createdSecretSet = createSecretSetVersion(projectId, request.environment, request.env + request.secretEnv, "manual_redeploy")
                serviceSet = createdSecretSet
                DeploymentConfigurations.update({ DeploymentConfigurations.id eq id }) {
                    it[DeploymentConfigurations.secretSetId] = createdSecretSet.id
                    it[DeploymentConfigurations.secretSetVersion] = createdSecretSet.version
                }
            }
            request to serviceSet
        } ?: return null
        return create(prepared.first, prepared.second)
    }

    fun latestForProject(projectId: UUID): DeploymentJobRecord? = transaction {
        val serviceId = defaultServiceId(projectId)
        DeploymentJobs.selectAll().where {
            (DeploymentJobs.projectId eq projectId) and
                ((DeploymentJobs.serviceId eq serviceId) or DeploymentJobs.serviceId.isNull())
        }.toList()
            .sortedWith(compareByDescending<ResultRow> {
                it[DeploymentJobs.serviceId] == serviceId
            }.thenByDescending { it[DeploymentJobs.createdAt] })
            .firstOrNull()?.toRecord()
    }
    fun cancel(id: UUID): Boolean = transaction {
        val deployment = com.gatekeeper.db.tables.Deployments.selectAll()
            .where { com.gatekeeper.db.tables.Deployments.id eq id }.singleOrNull() ?: return@transaction false
        val deploymentStatus = deployment[com.gatekeeper.db.tables.Deployments.status]
        if (deploymentStatus !in setOf(DeploymentStatus.QUEUED, DeploymentStatus.BUILDING, DeploymentStatus.STARTING, DeploymentStatus.HEALTH_CHECKING)) {
            return@transaction false
        }
        val changed = DeploymentJobs.update({
            (DeploymentJobs.id eq id) and
                (DeploymentJobs.status inList listOf("queued", "running", "awaiting_build", "awaiting_container")) and
                (DeploymentJobs.currentStep notInList listOf("readiness_succeeded", "cutover_in_progress"))
        }) {
            it[status] = "cancelled"; it[currentStep] = "cancelled"; it[completedAt] = LocalDateTime.now(); it[cancelledAt] = LocalDateTime.now(); it[updatedAt] = LocalDateTime.now()
        } > 0
        if (changed) check(DeploymentApplicationService.transition(id, DeploymentStatus.CANCELLED)) { "Deployment $id could not be cancelled" }
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
        val serviceId = request.serviceId?.let(UUID::fromString) ?: defaultServiceId(projectId)
        check(Services.selectAll().where { (Services.id eq serviceId) and (Services.projectId eq projectId) }.count() == 1L) {
            "Service does not belong to project"
        }
        validateReadiness(request.readinessType, request.readinessTarget, request.readinessTimeoutSeconds, request.readinessIntervalSeconds, request.readinessProbeTimeoutMillis)
        val sharedSet = sharedEnvironmentSet(
            projectId, environment, request.sharedEnvironmentSetId?.let(UUID::fromString), request.sharedEnvironmentSetVersion
        )
        val serviceSetValues = request.env + request.secretEnv
        val secretSet = existingSecretSet ?: serviceSetValues.takeIf { it.isNotEmpty() }?.let { values ->
            createSecretSetVersion(projectId, environment, values, request.triggerSource)
        }
        val resolvedServiceValues = if (existingSecretSet != null) serviceEnvironmentValues(existingSecretSet) else serviceSetValues
        val resolvedSources = buildMap {
            sharedEnvironmentValues(sharedSet).keys.forEach { put(it, "project_shared") }
            resolvedServiceValues.keys.forEach { put(it, "service") }
        }
        val volumesJson = Json.encodeToString(request.volumes)
        val registryCredentialId = request.registryCredentialId?.takeIf(String::isNotBlank)?.let(UUID::fromString)
        registryCredentialId?.let { requireRegistryCredential(it, request.registry) }
        // Save editable configuration and immutable execution snapshot before queuing work.
        DeploymentConfigurations.insert {
            it[DeploymentConfigurations.id] = id
            it[repository] = request.repository; it[gitRef] = request.gitRef; it[registry] = request.registry
            it[DeploymentConfigurations.registryCredentialId] = registryCredentialId
            it[imageName] = request.imageName; it[imageTag] = request.imageTag
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort; it[network] = request.network
            it[restartPolicy] = request.restartPolicy; it[DeploymentConfigurations.envJson] = "{}"
            it[DeploymentConfigurations.secretEnvEncrypted] = null; it[DeploymentConfigurations.volumesJson] = volumesJson
            it[DeploymentConfigurations.secretSetId] = secretSet?.id
            it[DeploymentConfigurations.secretSetVersion] = secretSet?.version
            it[DeploymentConfigurations.sharedEnvironmentSetId] = sharedSet?.id
            it[DeploymentConfigurations.sharedEnvironmentSetVersion] = sharedSet?.version
            it[createNetworkIfMissing] = request.createNetworkIfMissing; it[DeploymentConfigurations.autoDeploy] = request.autoDeploy
            it[DeploymentConfigurations.projectId] = projectId
            it[DeploymentConfigurations.serviceId] = serviceId
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
            it[DeploymentExecutions.registryCredentialId] = registryCredentialId
            it[imageName] = request.imageName; it[imageTag] = request.imageTag
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort; it[network] = request.network
            it[restartPolicy] = request.restartPolicy; it[DeploymentExecutions.envJson] = "{}"
            it[DeploymentExecutions.secretEnvEncrypted] = null; it[DeploymentExecutions.volumesJson] = volumesJson
            it[DeploymentExecutions.secretSetId] = secretSet?.id
            it[DeploymentExecutions.secretSetVersion] = secretSet?.version
            it[DeploymentExecutions.sharedEnvironmentSetId] = sharedSet?.id
            it[DeploymentExecutions.sharedEnvironmentSetVersion] = sharedSet?.version
            it[DeploymentExecutions.resolvedEnvironmentSourcesJson] = Json.encodeToString(resolvedSources)
            it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[DeploymentExecutions.projectId] = projectId
            it[DeploymentExecutions.serviceId] = serviceId
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
            it[DeploymentJobs.envJson] = "{}"
            it[DeploymentJobs.secretEnvEncrypted] = null
            it[DeploymentJobs.volumesJson] = volumesJson
            it[DeploymentJobs.createNetworkIfMissing] = request.createNetworkIfMissing
            it[DeploymentJobs.projectId] = projectId
            it[DeploymentJobs.serviceId] = serviceId
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
            serviceId = serviceId,
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
        val targetServiceId = target[com.gatekeeper.db.tables.Deployments.serviceId]
        check(ownerId != null && targetServiceId != null && com.gatekeeper.db.tables.Deployments.selectAll().where {
            (com.gatekeeper.db.tables.Deployments.serviceId eq targetServiceId) and
                (com.gatekeeper.db.tables.Deployments.environment eq environmentKey) and
                (com.gatekeeper.db.tables.Deployments.status eq DeploymentStatus.ACTIVE)
        }.count() == 1L) { "Rollback target must belong to the currently active service environment" }
        val execution = DeploymentExecutions.selectAll()
            .where { DeploymentExecutions.id eq target[com.gatekeeper.db.tables.Deployments.executionId] }.singleOrNull()
            ?: error("Rollback target execution snapshot not found")
        val legacySecrets = execution[DeploymentExecutions.secretEnvEncrypted]?.let {
            Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(it))
        }.orEmpty()
        val legacyEnv = Json.decodeFromString<Map<String, String>>(execution[DeploymentExecutions.envJson])
        val secretSet = execution[DeploymentExecutions.secretSetId]?.let { secretId ->
            execution[DeploymentExecutions.secretSetVersion]?.let { version -> SecretSetReference(secretId, version) }
        } ?: (legacyEnv + legacySecrets).takeIf { it.isNotEmpty() }?.let { values ->
            createSecretSetVersion(ownerId, environmentKey, values, "rollback")
        }
        val sharedSet = execution[DeploymentExecutions.sharedEnvironmentSetId]?.let { sharedId ->
            SharedEnvironmentSetReference(
                sharedId,
                execution[DeploymentExecutions.sharedEnvironmentSetVersion] ?: error("Shared environment version is missing")
            )
        }
        val resolvedSources = runCatching {
            Json.decodeFromString<Map<String, String>>(execution[DeploymentExecutions.resolvedEnvironmentSourcesJson])
                .takeIf { it.isNotEmpty() } ?: resolveEnvironmentForExecution(execution[DeploymentExecutions.id]).sourceByKey
        }.getOrElse { resolveEnvironmentForExecution(execution[DeploymentExecutions.id]).sourceByKey }
        val request = com.gatekeeper.deployment.CreateDeploymentRequest(
            repository = execution[DeploymentExecutions.repository],
            gitRef = execution[DeploymentExecutions.gitRef],
            registry = execution[DeploymentExecutions.registry],
            registryCredentialId = execution[DeploymentExecutions.registryCredentialId]?.toString(),
            imageName = execution[DeploymentExecutions.imageName],
            imageTag = execution[DeploymentExecutions.imageTag],
            projectId = ownerId.toString(),
            autoDeploy = false,
            hostPort = execution[DeploymentExecutions.hostPort],
            containerPort = execution[DeploymentExecutions.containerPort],
            network = execution[DeploymentExecutions.network],
            restartPolicy = execution[DeploymentExecutions.restartPolicy],
            triggerSource = "rollback",
            env = emptyMap(),
            secretEnv = emptyMap(),
            sharedEnvironmentSetId = sharedSet?.id?.toString(),
            sharedEnvironmentSetVersion = sharedSet?.version,
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
        val serviceId = execution[DeploymentExecutions.serviceId] ?: defaultServiceId(projectId)
        val volumesJson = Json.encodeToString(request.volumes)
        DeploymentConfigurations.insert {
            it[DeploymentConfigurations.id] = id
            it[repository] = request.repository; it[gitRef] = request.gitRef; it[registry] = request.registry
            it[imageName] = request.imageName; it[imageTag] = request.imageTag
            it[hostPort] = request.hostPort; it[containerPort] = request.containerPort
            it[network] = request.network; it[restartPolicy] = request.restartPolicy
            it[DeploymentConfigurations.envJson] = "{}"; it[DeploymentConfigurations.secretEnvEncrypted] = null
            it[DeploymentConfigurations.secretSetId] = secretSet?.id
            it[DeploymentConfigurations.secretSetVersion] = secretSet?.version
            it[DeploymentConfigurations.sharedEnvironmentSetId] = sharedSet?.id
            it[DeploymentConfigurations.sharedEnvironmentSetVersion] = sharedSet?.version
            it[DeploymentConfigurations.volumesJson] = volumesJson; it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[DeploymentConfigurations.autoDeploy] = request.autoDeploy; it[DeploymentConfigurations.projectId] = projectId
            it[DeploymentConfigurations.serviceId] = serviceId
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
            it[DeploymentExecutions.envJson] = "{}"; it[DeploymentExecutions.secretEnvEncrypted] = null
            it[DeploymentExecutions.secretSetId] = secretSet?.id
            it[DeploymentExecutions.secretSetVersion] = secretSet?.version
            it[DeploymentExecutions.sharedEnvironmentSetId] = sharedSet?.id
            it[DeploymentExecutions.sharedEnvironmentSetVersion] = sharedSet?.version
            it[DeploymentExecutions.resolvedEnvironmentSourcesJson] = Json.encodeToString(resolvedSources)
            it[DeploymentExecutions.volumesJson] = volumesJson; it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[DeploymentExecutions.projectId] = projectId
            it[DeploymentExecutions.serviceId] = serviceId
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
            it[DeploymentJobs.envJson] = "{}"; it[DeploymentJobs.secretEnvEncrypted] = null
            it[DeploymentJobs.volumesJson] = volumesJson; it[createNetworkIfMissing] = request.createNetworkIfMissing
            it[DeploymentJobs.projectId] = projectId
            it[DeploymentJobs.serviceId] = serviceId
            it[DeploymentJobs.environment] = environment; it[DeploymentJobs.readinessType] = request.readinessType
            it[DeploymentJobs.readinessTarget] = request.readinessTarget
            it[DeploymentJobs.readinessTimeoutSeconds] = request.readinessTimeoutSeconds
            it[DeploymentJobs.readinessIntervalSeconds] = request.readinessIntervalSeconds
            it[DeploymentJobs.readinessProbeTimeoutMillis] = request.readinessProbeTimeoutMillis
            it[DeploymentJobs.triggerSource] = "rollback"; it[status] = "queued"; it[currentStep] = "queued"
        }
        DeploymentApplicationService.createQueued(
            id = id, projectId = projectId, configurationId = id, executionId = id,
            serviceId = serviceId,
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
        createdBy: String,
        serviceId: UUID = defaultServiceId(projectId)
    ): SecretSetReference {
        check(SecretValueCipher.isConfigured()) { "Deployment secret encryption is not configured" }
        val nextVersion = (ProjectSecretSetVersions.selectAll().where {
            (ProjectSecretSetVersions.serviceId eq serviceId) and
                (ProjectSecretSetVersions.environment eq environment)
        }.map { it[ProjectSecretSetVersions.version] }.maxOrNull() ?: 0) + 1
        val id = UUID.randomUUID()
        ProjectSecretSetVersions.insert {
            it[ProjectSecretSetVersions.id] = id
            it[ProjectSecretSetVersions.projectId] = projectId
            it[ProjectSecretSetVersions.serviceId] = serviceId
            it[ProjectSecretSetVersions.environment] = environment
            it[ProjectSecretSetVersions.version] = nextVersion
            it[ProjectSecretSetVersions.encryptedPayload] = SecretValueCipher.encrypt(Json.encodeToString(values))
            it[ProjectSecretSetVersions.createdAt] = LocalDateTime.now()
            it[ProjectSecretSetVersions.createdBy] = createdBy
        }
        return SecretSetReference(id, nextVersion)
    }

    private fun latestServiceSecretSet(projectId: UUID, serviceId: UUID, environment: String): SecretSetReference? =
        ProjectSecretSetVersions.selectAll().where {
            (ProjectSecretSetVersions.projectId eq projectId) and
                (ProjectSecretSetVersions.serviceId eq serviceId) and
                (ProjectSecretSetVersions.environment eq environment)
        }.maxByOrNull { it[ProjectSecretSetVersions.version] }?.let {
            SecretSetReference(it[ProjectSecretSetVersions.id], it[ProjectSecretSetVersions.version])
        }

    fun find(id: UUID): DeploymentJobRecord? = transaction {
        DeploymentJobs.selectAll().where { DeploymentJobs.id eq id }.singleOrNull()?.toRecord()
    }

    data class ResolvedEnvironment(val values: Map<String, String>, val sourceByKey: Map<String, String>)

    /** Resolve pinned shared and service versions for the worker without persisting their values in the snapshot. */
    fun resolveEnvironmentForExecution(executionId: UUID): ResolvedEnvironment = transaction {
        val execution = DeploymentExecutions.selectAll().where { DeploymentExecutions.id eq executionId }.singleOrNull()
            ?: error("Deployment execution $executionId not found")
        val projectId = execution[DeploymentExecutions.projectId]
        val serviceId = execution[DeploymentExecutions.serviceId]
        val environment = execution[DeploymentExecutions.environment] ?: "production"
        val sharedId = execution[DeploymentExecutions.sharedEnvironmentSetId]
        val sharedVersion = execution[DeploymentExecutions.sharedEnvironmentSetVersion]
        val sharedValues = if (sharedId != null) {
            val version = sharedVersion ?: error("Shared environment version is missing for execution $executionId")
            val row = ProjectSharedEnvironmentVersions.selectAll().where {
                (ProjectSharedEnvironmentVersions.id eq sharedId) and
                    (ProjectSharedEnvironmentVersions.version eq version)
            }.singleOrNull() ?: error("Shared environment set $sharedId/$version is unavailable")
            check(row[ProjectSharedEnvironmentVersions.projectId] == projectId) { "Shared environment project does not match execution $executionId" }
            check(row[ProjectSharedEnvironmentVersions.environment] == environment) { "Shared environment scope does not match execution $executionId" }
            Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(row[ProjectSharedEnvironmentVersions.encryptedPayload]))
        } else emptyMap()

        val serviceSetId = execution[DeploymentExecutions.secretSetId]
        val serviceSetVersion = execution[DeploymentExecutions.secretSetVersion]
        val serviceValues = if (serviceSetId != null) {
            val version = serviceSetVersion ?: error("Service environment version is missing for execution $executionId")
            val row = ProjectSecretSetVersions.selectAll().where {
                (ProjectSecretSetVersions.id eq serviceSetId) and (ProjectSecretSetVersions.version eq version)
            }.singleOrNull() ?: error("Service environment set $serviceSetId/$version is unavailable")
            check(row[ProjectSecretSetVersions.projectId] == projectId) { "Service environment project does not match execution $executionId" }
            check(serviceId == null || row[ProjectSecretSetVersions.serviceId] == serviceId) { "Service environment owner does not match execution $executionId" }
            check(row[ProjectSecretSetVersions.environment] == environment) { "Service environment scope does not match execution $executionId" }
            Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(row[ProjectSecretSetVersions.encryptedPayload]))
        } else {
            val legacy = execution[DeploymentExecutions.secretEnvEncrypted]?.let {
                Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(it))
            }.orEmpty()
            Json.decodeFromString<Map<String, String>>(execution[DeploymentExecutions.envJson]) + legacy
        }
        val sources = buildMap {
            sharedValues.keys.forEach { put(it, "project_shared") }
            serviceValues.keys.forEach { put(it, "service") }
        }
        ResolvedEnvironment(sharedValues + serviceValues, sources)
    }

    /** Backward-compatible name retained for callers that expect only the environment map. */
    fun resolveSecretEnvForExecution(executionId: UUID): Map<String, String> =
        resolveEnvironmentForExecution(executionId).values

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
        val deploymentSecrets = runCatching { resolveEnvironmentForExecution(id).values.values }.getOrElse {
            existing[DeploymentJobs.secretEnvEncrypted]?.let { encoded ->
                Json.decodeFromString<Map<String, String>>(SecretValueCipher.decrypt(encoded)).values
            }.orEmpty()
        }
        val redactedError = error?.let { SecretValueCipher.redact(it, deploymentSecrets) }
        DeploymentJobs.update({ DeploymentJobs.id eq id }) {
            step?.let { value -> it[currentStep] = value }
            log?.let { value -> it[logs] = existing[DeploymentJobs.logs] + SecretValueCipher.redact(value,
                deploymentSecrets
            ) + "\n" }
            commitSha?.let { value -> it[DeploymentJobs.commitSha] = value }
            imageDigest?.let { value -> it[DeploymentJobs.imageDigest] = value }
            status?.let { value -> it[DeploymentJobs.status] = value }
            error?.let { value -> it[errorMessage] = SecretValueCipher.redact(value, deploymentSecrets) }
            if (status == "succeeded" || status == "failed") it[completedAt] = LocalDateTime.now()
            it[updatedAt] = LocalDateTime.now()
        }
        DeploymentExecutions.update({ DeploymentExecutions.id eq id }) {
            step?.let { value -> it[currentStep] = value }
            log?.let { value -> it[logs] = (DeploymentExecutions.selectAll().where { DeploymentExecutions.id eq id }.singleOrNull()?.get(DeploymentExecutions.logs).orEmpty()) + SecretValueCipher.redact(value,
                deploymentSecrets
            ) + "\n" }
            commitSha?.let { value -> it[DeploymentExecutions.commitSha] = value }
            imageDigest?.let { value -> it[DeploymentExecutions.imageDigest] = value }
            status?.let { value -> it[DeploymentExecutions.status] = value }
            error?.let { value -> it[DeploymentExecutions.errorMessage] = SecretValueCipher.redact(value,
                deploymentSecrets
            ) }
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
        execution?.get(DeploymentExecutions.secretSetId), execution?.get(DeploymentExecutions.secretSetVersion),
        this[DeploymentJobs.serviceId] ?: defaultServiceId(this[DeploymentJobs.projectId])
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
