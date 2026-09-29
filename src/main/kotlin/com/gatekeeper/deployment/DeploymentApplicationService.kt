package com.gatekeeper.deployment

import com.gatekeeper.db.tables.Deployments
import com.gatekeeper.db.tables.DeploymentStatus
import com.gatekeeper.db.tables.Projects
import com.gatekeeper.db.tables.AuditLog
import com.gatekeeper.db.tables.Services
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

/** The only writer of canonical deployment lifecycle state. */
object DeploymentApplicationService {
    private fun defaultServiceId(projectId: UUID): UUID = Services.selectAll().where {
        (Services.projectId eq projectId) and (Services.isDefault eq true)
    }.singleOrNull()?.get(Services.id) ?: error("Default service not found for project $projectId")

    private val allowedTransitions = mapOf(
        DeploymentStatus.QUEUED to setOf(DeploymentStatus.BUILDING, DeploymentStatus.CANCELLED),
        DeploymentStatus.BUILDING to setOf(DeploymentStatus.STARTING, DeploymentStatus.FAILED, DeploymentStatus.CANCELLED),
        DeploymentStatus.STARTING to setOf(DeploymentStatus.HEALTH_CHECKING, DeploymentStatus.FAILED, DeploymentStatus.CANCELLED),
        DeploymentStatus.HEALTH_CHECKING to setOf(DeploymentStatus.QUEUED, DeploymentStatus.ACTIVE, DeploymentStatus.FAILED, DeploymentStatus.CANCELLED),
        DeploymentStatus.ACTIVE to setOf(DeploymentStatus.SUPERSEDED, DeploymentStatus.ROLLED_BACK),
        DeploymentStatus.SUPERSEDED to setOf(DeploymentStatus.ACTIVE, DeploymentStatus.ROLLED_BACK),
        DeploymentStatus.FAILED to setOf(DeploymentStatus.QUEUED),
        DeploymentStatus.CANCELLED to setOf(DeploymentStatus.QUEUED),
        DeploymentStatus.ROLLED_BACK to emptySet()
    )

    fun createQueued(
        id: UUID,
        projectId: UUID,
        serviceId: UUID,
        configurationId: UUID,
        executionId: UUID,
        triggerSource: String,
        environment: String = "production",
        rolledBackToDeploymentId: UUID? = null,
        credentialSetId: UUID? = null,
        credentialSetVersion: Int? = null,
        secretSetId: UUID? = null,
        secretSetVersion: Int? = null
    ) = transaction {
        val now = LocalDateTime.now()
        Deployments.insert {
            it[Deployments.id] = id
            it[Deployments.projectId] = projectId
            it[Deployments.serviceId] = serviceId
            it[Deployments.environment] = environment
            it[Deployments.configurationId] = configurationId
            it[Deployments.executionId] = executionId
            it[Deployments.credentialSetId] = credentialSetId
            it[Deployments.credentialSetVersion] = credentialSetVersion
            it[Deployments.secretSetId] = secretSetId
            it[Deployments.secretSetVersion] = secretSetVersion
            it[status] = DeploymentStatus.QUEUED
            it[Deployments.triggerSource] = triggerSource
            it[Deployments.rolledBackToDeploymentId] = rolledBackToDeploymentId
            it[queuedAt] = now
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    /** Persist an already-running Docker runtime as an active deployment without asking the worker to recreate it. */
    fun recordAdoptedRuntime(
        id: UUID,
        projectId: UUID,
        serviceId: UUID,
        configurationId: UUID,
        executionId: UUID,
        environment: String,
        triggerSource: String,
        containerName: String,
        hostPort: Int,
        containerPort: Int,
        portMappings: Map<Int, Int>,
        imageDigest: String?,
        secretSetId: UUID?,
        secretSetVersion: Int?
    ): Boolean = transaction {
        val now = LocalDateTime.now()
        Deployments.insert {
            it[Deployments.id] = id
            it[Deployments.projectId] = projectId
            it[Deployments.serviceId] = serviceId
            it[Deployments.environment] = environment
            it[Deployments.configurationId] = configurationId
            it[Deployments.executionId] = executionId
            it[Deployments.secretSetId] = secretSetId
            it[Deployments.secretSetVersion] = secretSetVersion
            it[status] = DeploymentStatus.HEALTH_CHECKING
            it[Deployments.runtimeContainerName] = containerName
            it[Deployments.runtimeHostPort] = hostPort
            it[Deployments.runtimePortsJson] = kotlinx.serialization.json.Json.encodeToString(portMappings.mapKeys { entry -> entry.key.toString() })
            it[Deployments.triggerSource] = triggerSource
            it[healthCheckingAt] = now
            it[createdAt] = now
            it[updatedAt] = now
        }
        com.gatekeeper.db.tables.DeploymentExecutions.update({ com.gatekeeper.db.tables.DeploymentExecutions.id eq executionId }) {
            it[com.gatekeeper.db.tables.DeploymentExecutions.imageDigest] = imageDigest
            it[com.gatekeeper.db.tables.DeploymentExecutions.status] = "succeeded"
            it[com.gatekeeper.db.tables.DeploymentExecutions.currentStep] = "readiness_succeeded"
            it[com.gatekeeper.db.tables.DeploymentExecutions.completedAt] = now
            it[com.gatekeeper.db.tables.DeploymentExecutions.updatedAt] = now
        }
        true
    }

    fun recordCandidateRuntime(id: UUID, containerName: String, hostPort: Int?, containerPort: Int?, portMappings: Map<Int, Int> = emptyMap()): Boolean = transaction {
        require(containerName.isNotBlank()) { "Candidate container name must not be blank" }
        require(hostPort == null || hostPort in 1..65535) { "Candidate host port must be between 1 and 65535" }
        require(containerPort == null || containerPort in 1..65535) { "Candidate container port must be between 1 and 65535" }
        require(portMappings.all { (containerPort, mappedHostPort) -> containerPort in 1..65535 && mappedHostPort in 1..65535 }) { "Candidate port mapping is invalid" }
        val deployment = Deployments.selectAll().where {
            (Deployments.id eq id) and (Deployments.status eq DeploymentStatus.HEALTH_CHECKING)
        }.singleOrNull() ?: return@transaction false
        val updated = Deployments.update({ (Deployments.id eq id) and (Deployments.status eq DeploymentStatus.HEALTH_CHECKING) }) {
            it[Deployments.runtimeContainerName] = containerName
            it[Deployments.runtimeHostPort] = hostPort
            it[Deployments.runtimePortsJson] = kotlinx.serialization.json.Json.encodeToString(portMappings.mapKeys { it.key.toString() })
            it[updatedAt] = LocalDateTime.now()
        } == 1
        if (!updated) return@transaction false
        if (containerPort != null) {
            val executionId = deployment[Deployments.executionId]
            com.gatekeeper.db.tables.DeploymentExecutions.update({ com.gatekeeper.db.tables.DeploymentExecutions.id eq executionId }) {
                it[com.gatekeeper.db.tables.DeploymentExecutions.containerPort] = containerPort
            }
            val configurationId = deployment[Deployments.configurationId]
            com.gatekeeper.db.tables.DeploymentConfigurations.update({ com.gatekeeper.db.tables.DeploymentConfigurations.id eq configurationId }) {
                it[com.gatekeeper.db.tables.DeploymentConfigurations.containerPort] = containerPort
                it[com.gatekeeper.db.tables.DeploymentConfigurations.updatedAt] = LocalDateTime.now()
            }
            com.gatekeeper.db.tables.DeploymentJobs.update({ com.gatekeeper.db.tables.DeploymentJobs.id eq id }) {
                it[com.gatekeeper.db.tables.DeploymentJobs.containerPort] = containerPort
            }
        }
        true
    }

    fun recordCredentialReference(id: UUID, credentialId: UUID, version: Int): Boolean = transaction {
        require(version > 0) { "Credential version must be positive" }
        Deployments.update({
            (Deployments.id eq id) and (Deployments.status inList listOf(
                DeploymentStatus.QUEUED, DeploymentStatus.BUILDING, DeploymentStatus.STARTING, DeploymentStatus.HEALTH_CHECKING
            ))
        }) {
            it[Deployments.credentialSetId] = credentialId
            it[Deployments.credentialSetVersion] = version
            it[updatedAt] = LocalDateTime.now()
        } == 1
    }

    fun candidateContainerName(id: UUID): String? = transaction {
        Deployments.selectAll().where { Deployments.id eq id }.singleOrNull()?.get(Deployments.runtimeContainerName)
    }

    fun clearCandidateRuntime(id: UUID): Boolean = transaction {
        Deployments.update({ (Deployments.id eq id) and (Deployments.status inList listOf(DeploymentStatus.QUEUED, DeploymentStatus.BUILDING, DeploymentStatus.STARTING)) }) {
            it[Deployments.runtimeContainerName] = null
            it[Deployments.runtimeHostPort] = null
            it[Deployments.runtimePortsJson] = "{}"
            it[updatedAt] = LocalDateTime.now()
        } == 1
    }

    data class CandidateRuntime(
        val projectId: UUID, val projectSlug: String?, val name: String?, val hostPort: Int?,
        val ports: Map<Int, Int>, val status: DeploymentStatus
    )

    fun candidateRuntime(id: UUID): CandidateRuntime? = transaction {
        val deployment = Deployments.selectAll().where { Deployments.id eq id }.singleOrNull() ?: return@transaction null
        val projectId = deployment[Deployments.projectId] ?: error("Deployment has no project_id")
        val slug = Projects.selectAll().where { Projects.id eq projectId }.singleOrNull()?.get(Projects.slug)
        val ports = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>(deployment[Deployments.runtimePortsJson]).mapKeys { it.key.toInt() }
        }.getOrDefault(emptyMap())
        CandidateRuntime(projectId, slug, deployment[Deployments.runtimeContainerName], deployment[Deployments.runtimeHostPort], ports, deployment[Deployments.status])
    }

    data class ActiveRuntime(
        val id: UUID, val executionId: UUID, val name: String?, val hostPort: Int?, val image: String?, val hostConfigPort: Int?,
        val containerPort: Int?, val network: String, val restartPolicy: String,
        val env: Map<String, String>, val secretEnv: Map<String, String>, val volumes: List<com.gatekeeper.docker.VolumeMount>
    )

    fun activeRuntime(serviceId: UUID, environment: String): ActiveRuntime? = transaction {
        Deployments.selectAll().where {
            (Deployments.serviceId eq serviceId) and (Deployments.environment eq environment) and (Deployments.status eq DeploymentStatus.ACTIVE)
        }.singleOrNull()?.let { row ->
            val execution = com.gatekeeper.db.tables.DeploymentExecutions.selectAll()
                .where { com.gatekeeper.db.tables.DeploymentExecutions.id eq row[Deployments.executionId] }.singleOrNull()
            val image = execution?.let { "${it[com.gatekeeper.db.tables.DeploymentExecutions.imageName]}:${it[com.gatekeeper.db.tables.DeploymentExecutions.imageTag]}" }
            val env = execution?.let { kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(it[com.gatekeeper.db.tables.DeploymentExecutions.envJson]) }.orEmpty()
            val volumes = execution?.let { kotlinx.serialization.json.Json.decodeFromString<List<com.gatekeeper.docker.VolumeMount>>(it[com.gatekeeper.db.tables.DeploymentExecutions.volumesJson]) }.orEmpty()
            ActiveRuntime(
                row[Deployments.id], row[Deployments.executionId], row[Deployments.runtimeContainerName], row[Deployments.runtimeHostPort], image,
                execution?.get(com.gatekeeper.db.tables.DeploymentExecutions.hostPort),
                execution?.get(com.gatekeeper.db.tables.DeploymentExecutions.containerPort),
                execution?.get(com.gatekeeper.db.tables.DeploymentExecutions.network) ?: "bridge",
                execution?.get(com.gatekeeper.db.tables.DeploymentExecutions.restartPolicy) ?: "unless-stopped",
                env, emptyMap(), volumes
            )
        }
    }

    data class ActiveDeploymentRuntime(
        val id: UUID,
        val projectId: UUID,
        val environment: String,
        val status: DeploymentStatus,
        val containerName: String?,
        val containerPort: Int?,
        val publishedPorts: Map<Int, Int>,
        val serviceId: UUID = UUID(0, 0)
    )

    data class ActiveDeploymentSummary(
        val id: UUID,
        val projectId: UUID,
        val environment: String,
        val status: String,
        val triggerSource: String,
        val createdAt: LocalDateTime,
        val activeAt: LocalDateTime?,
        val containerName: String?,
        val runtimeHostPort: Int?,
        val runtimePorts: Map<Int, Int>,
        val imageName: String,
        val imageTag: String,
        val imageDigest: String?,
        val commitSha: String?,
        val credentialSetId: UUID?,
        val credentialSetVersion: Int?,
        val secretSetId: UUID?,
        val secretSetVersion: Int?
    )

    data class DeploymentHistoryEntry(
        val id: UUID,
        val projectId: UUID,
        val environment: String,
        val status: String,
        val sourceCommit: String?,
        val imageName: String,
        val imageTag: String,
        val imageDigest: String?,
        val triggerSource: String,
        val actor: String?,
        val createdAt: LocalDateTime,
        val activeAt: LocalDateTime?,
        val failureReason: String?,
        val healthCheckResult: String,
        val credentialSetId: UUID?,
        val credentialSetVersion: Int?,
        val secretSetId: UUID?,
        val secretSetVersion: Int?,
        val canRollback: Boolean,
        val canRedeploy: Boolean,
        val canCancel: Boolean,
        val configurationId: UUID
    )

    /** History metadata only: never reads encrypted environment or credential payloads. */
    fun deploymentHistory(projectId: UUID, environment: String, serviceId: UUID? = null): List<DeploymentHistoryEntry> = transaction {
        val deployments = Deployments.selectAll().where {
            (Deployments.projectId eq projectId) and (Deployments.environment eq environment)
        }.toList().filter { serviceId == null || it[Deployments.serviceId] == serviceId }
            .sortedByDescending { it[Deployments.createdAt] }
        val latestDeploymentByService = deployments.groupBy { it[Deployments.serviceId] }
            .mapValues { (_, rows) -> rows.first()[Deployments.id] }
        deployments.mapNotNull { deployment ->
            val execution = com.gatekeeper.db.tables.DeploymentExecutions.selectAll()
                .where { com.gatekeeper.db.tables.DeploymentExecutions.id eq deployment[Deployments.executionId] }
                .singleOrNull() ?: return@mapNotNull null
            val status = deployment[Deployments.status]
            val currentStep = execution[com.gatekeeper.db.tables.DeploymentExecutions.currentStep]
            val canCancel = status in setOf(DeploymentStatus.QUEUED, DeploymentStatus.BUILDING, DeploymentStatus.STARTING, DeploymentStatus.HEALTH_CHECKING) &&
                currentStep !in setOf("readiness_succeeded", "cutover_in_progress")
            val healthResult = when {
                currentStep == "readiness_succeeded" || currentStep == "cutover_in_progress" ||
                    status == DeploymentStatus.ACTIVE || status == DeploymentStatus.SUPERSEDED || status == DeploymentStatus.ROLLED_BACK -> "passed"
                status == DeploymentStatus.FAILED && currentStep == "health_checking" -> "failed"
                status == DeploymentStatus.HEALTH_CHECKING -> "running"
                status == DeploymentStatus.FAILED -> "not_passed"
                else -> "not_run"
            }
            val targetId = deployment[Deployments.id]
            val canRollback = status == DeploymentStatus.SUPERSEDED && deployments.any {
                it[Deployments.status] == DeploymentStatus.ACTIVE && it[Deployments.environment] == environment
                    && it[Deployments.serviceId] == deployment[Deployments.serviceId]
            }
            DeploymentHistoryEntry(
                targetId, deployment[Deployments.projectId] ?: error("Deployment has no project_id"), deployment[Deployments.environment], status.value,
                execution[com.gatekeeper.db.tables.DeploymentExecutions.commitSha],
                execution[com.gatekeeper.db.tables.DeploymentExecutions.imageName],
                execution[com.gatekeeper.db.tables.DeploymentExecutions.imageTag],
                execution[com.gatekeeper.db.tables.DeploymentExecutions.imageDigest],
                deployment[Deployments.triggerSource], null,
                deployment[Deployments.createdAt], deployment[Deployments.activeAt], deployment[Deployments.failureReason], healthResult,
                deployment[Deployments.credentialSetId], deployment[Deployments.credentialSetVersion],
                deployment[Deployments.secretSetId] ?: execution[com.gatekeeper.db.tables.DeploymentExecutions.secretSetId],
                deployment[Deployments.secretSetVersion] ?: execution[com.gatekeeper.db.tables.DeploymentExecutions.secretSetVersion],
                canRollback,
                status == DeploymentStatus.ACTIVE ||
                    status == DeploymentStatus.FAILED && latestDeploymentByService[deployment[Deployments.serviceId]] == targetId,
                canCancel,
                deployment[Deployments.configurationId]
            )
        }
    }

    fun deploymentHistoryPage(limit: Int, offset: Int): Pair<List<Pair<String, DeploymentHistoryEntry>>, Long> = transaction {
        val rows = Deployments.selectAll()
            .orderBy(Deployments.createdAt to org.jetbrains.exposed.sql.SortOrder.DESC)
        val total = rows.count()
        val latestDeploymentByService = Deployments.selectAll().toList()
            .groupBy { it[Deployments.serviceId] to it[Deployments.environment] }
            .mapValues { (_, serviceDeployments) ->
                serviceDeployments.maxByOrNull { it[Deployments.createdAt] }?.get(Deployments.id)
            }
        val page = rows.limit(limit, offset.toLong()).mapNotNull { deployment ->
            val projectId = deployment[Deployments.projectId] ?: error("Deployment has no project_id")
            val project = Projects.selectAll().where { Projects.id eq projectId }.singleOrNull() ?: return@mapNotNull null
            val execution = com.gatekeeper.db.tables.DeploymentExecutions.selectAll()
                .where { com.gatekeeper.db.tables.DeploymentExecutions.id eq deployment[Deployments.executionId] }
                .singleOrNull() ?: return@mapNotNull null
            val environment = deployment[Deployments.environment]
            val status = deployment[Deployments.status]
            val currentStep = execution[com.gatekeeper.db.tables.DeploymentExecutions.currentStep]
            val canCancel = status in setOf(DeploymentStatus.QUEUED, DeploymentStatus.BUILDING, DeploymentStatus.STARTING, DeploymentStatus.HEALTH_CHECKING) &&
                currentStep !in setOf("readiness_succeeded", "cutover_in_progress")
            val health = when {
                currentStep == "readiness_succeeded" || currentStep == "cutover_in_progress" ||
                    status == DeploymentStatus.ACTIVE || status == DeploymentStatus.SUPERSEDED || status == DeploymentStatus.ROLLED_BACK -> "passed"
                status == DeploymentStatus.FAILED && currentStep == "health_checking" -> "failed"
                status == DeploymentStatus.HEALTH_CHECKING -> "running"
                status == DeploymentStatus.FAILED -> "not_passed"
                else -> "not_run"
            }
            val hasActive = Deployments.selectAll().where {
                (Deployments.serviceId eq deployment[Deployments.serviceId]) and
                    (Deployments.environment eq environment) and (Deployments.status eq DeploymentStatus.ACTIVE)
            }.count() > 0
            val entry = DeploymentHistoryEntry(
                deployment[Deployments.id], projectId, environment, status.value,
                execution[com.gatekeeper.db.tables.DeploymentExecutions.commitSha],
                execution[com.gatekeeper.db.tables.DeploymentExecutions.imageName], execution[com.gatekeeper.db.tables.DeploymentExecutions.imageTag],
                execution[com.gatekeeper.db.tables.DeploymentExecutions.imageDigest], deployment[Deployments.triggerSource], null,
                deployment[Deployments.createdAt], deployment[Deployments.activeAt], deployment[Deployments.failureReason], health,
                deployment[Deployments.credentialSetId], deployment[Deployments.credentialSetVersion],
                deployment[Deployments.secretSetId] ?: execution[com.gatekeeper.db.tables.DeploymentExecutions.secretSetId],
                deployment[Deployments.secretSetVersion] ?: execution[com.gatekeeper.db.tables.DeploymentExecutions.secretSetVersion],
                status == DeploymentStatus.SUPERSEDED && hasActive,
                status == DeploymentStatus.ACTIVE ||
                    status == DeploymentStatus.FAILED && latestDeploymentByService[deployment[Deployments.serviceId] to environment] == deployment[Deployments.id],
                canCancel,
                deployment[Deployments.configurationId]
            )
            project[Projects.slug] to entry
        }
        page to total
    }

    /** Metadata for the active pointer only. Never selects or decrypts environment values. */
    fun activeDeploymentSummary(projectId: UUID, environment: String): ActiveDeploymentSummary? =
        activeDeploymentSummaryForService(defaultServiceId(projectId), environment)

    fun activeDeploymentSummaryForService(serviceId: UUID, environment: String): ActiveDeploymentSummary? = transaction {
        val deployment = Deployments.selectAll().where {
            (Deployments.serviceId eq serviceId) and (Deployments.environment eq environment) and
                (Deployments.status eq DeploymentStatus.ACTIVE)
        }.singleOrNull() ?: return@transaction null
        val projectId = deployment[Deployments.projectId] ?: return@transaction null
        val execution = com.gatekeeper.db.tables.DeploymentExecutions.selectAll()
            .where { com.gatekeeper.db.tables.DeploymentExecutions.id eq deployment[Deployments.executionId] }
            .singleOrNull() ?: return@transaction null
        val runtimePorts = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>(deployment[Deployments.runtimePortsJson])
                .mapKeys { it.key.toInt() }
        }.getOrDefault(emptyMap())
        ActiveDeploymentSummary(
            deployment[Deployments.id], projectId, deployment[Deployments.environment], deployment[Deployments.status].value,
            deployment[Deployments.triggerSource], deployment[Deployments.createdAt], deployment[Deployments.activeAt],
            deployment[Deployments.runtimeContainerName], deployment[Deployments.runtimeHostPort], runtimePorts,
            execution[com.gatekeeper.db.tables.DeploymentExecutions.imageName],
            execution[com.gatekeeper.db.tables.DeploymentExecutions.imageTag],
            execution[com.gatekeeper.db.tables.DeploymentExecutions.imageDigest],
            execution[com.gatekeeper.db.tables.DeploymentExecutions.commitSha],
            deployment[Deployments.credentialSetId], deployment[Deployments.credentialSetVersion],
            deployment[Deployments.secretSetId], deployment[Deployments.secretSetVersion]
        )
    }

    fun latestDeploymentState(projectId: UUID, environment: String): Pair<UUID, String>? =
        latestDeploymentStateForService(defaultServiceId(projectId), environment)

    fun latestDeploymentStateForService(serviceId: UUID, environment: String): Pair<UUID, String>? = transaction {
        Deployments.selectAll().where {
            (Deployments.serviceId eq serviceId) and (Deployments.environment eq environment)
        }.maxByOrNull { it[Deployments.createdAt] }?.let { it[Deployments.id] to it[Deployments.status].value }
    }

    /** Lightweight active-pointer lookup for gateway resolution; never loads secret environment values. */
    fun activeDeploymentRuntime(serviceId: UUID, environment: String): ActiveDeploymentRuntime? = transaction {
        val row = Deployments.selectAll().where {
            (Deployments.serviceId eq serviceId) and (Deployments.environment eq environment) and
                (Deployments.status eq DeploymentStatus.ACTIVE)
        }.singleOrNull() ?: return@transaction null
        val containerPort = com.gatekeeper.db.tables.DeploymentExecutions.selectAll()
            .where { com.gatekeeper.db.tables.DeploymentExecutions.id eq row[Deployments.executionId] }
            .singleOrNull()?.get(com.gatekeeper.db.tables.DeploymentExecutions.containerPort)
        val publishedPorts = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>(row[Deployments.runtimePortsJson])
                .mapKeys { it.key.toInt() }
        }.getOrDefault(emptyMap())
        ActiveDeploymentRuntime(
            row[Deployments.id], row[Deployments.projectId] ?: error("Active deployment has no project_id"),
            row[Deployments.environment], row[Deployments.status], row[Deployments.runtimeContainerName], containerPort, publishedPorts,
            row[Deployments.serviceId] ?: error("Active deployment has no service_id")
        )
    }

    data class RollbackArtifact(val image: String, val commitSha: String?)

    fun rollbackTargetId(id: UUID): UUID? = transaction {
        Deployments.selectAll().where { Deployments.id eq id }.singleOrNull()?.get(Deployments.rolledBackToDeploymentId)
    }

    fun rollbackArtifact(id: UUID): RollbackArtifact? = transaction {
        val deployment = Deployments.selectAll().where { Deployments.id eq id }.singleOrNull() ?: return@transaction null
        val targetId = deployment[Deployments.rolledBackToDeploymentId] ?: return@transaction null
        val target = Deployments.selectAll().where { Deployments.id eq targetId }.singleOrNull() ?: return@transaction null
        val execution = com.gatekeeper.db.tables.DeploymentExecutions.selectAll()
            .where { com.gatekeeper.db.tables.DeploymentExecutions.id eq target[Deployments.executionId] }.singleOrNull()
            ?: return@transaction null
        val registry = execution[com.gatekeeper.db.tables.DeploymentExecutions.registry].trim().trimEnd('/')
        val name = execution[com.gatekeeper.db.tables.DeploymentExecutions.imageName]
        val image = rollbackImageReference(
            registry,
            name,
            execution[com.gatekeeper.db.tables.DeploymentExecutions.imageDigest],
            execution[com.gatekeeper.db.tables.DeploymentExecutions.imageTag]
        )
        RollbackArtifact(image, execution[com.gatekeeper.db.tables.DeploymentExecutions.commitSha])
    }

    /** Promote only after the external gateway has accepted the candidate and old runtime cleanup succeeded. */
    fun activateAfterCutover(id: UUID, replacesDeploymentId: UUID?): Boolean = transaction {
        val row = Deployments.selectAll().where { Deployments.id eq id }.singleOrNull() ?: return@transaction false
        check(row[Deployments.status] == DeploymentStatus.HEALTH_CHECKING) { "Deployment $id is not health-checking" }
        val projectId = row[Deployments.projectId] ?: error("Deployment has no project_id")
        val serviceId = row[Deployments.serviceId] ?: error("Deployment has no service_id")
        val environment = row[Deployments.environment]
        val activeRows = Deployments.selectAll().where {
            (Deployments.serviceId eq serviceId) and (Deployments.environment eq environment) and (Deployments.status eq DeploymentStatus.ACTIVE)
        }.toList()
        check(activeRows.size <= 1) { "Multiple active deployments found for $serviceId/$environment" }
        val previousId = activeRows.singleOrNull()?.get(Deployments.id)
        if (replacesDeploymentId != null) check(previousId == replacesDeploymentId) { "Active deployment changed during cutover" }
        val now = LocalDateTime.now()
        previousId?.let { oldId ->
            val updated = Deployments.update({ (Deployments.id eq oldId) and (Deployments.status eq DeploymentStatus.ACTIVE) }) {
                it[status] = DeploymentStatus.SUPERSEDED
                it[supersededAt] = now
                it[updatedAt] = now
            }
            check(updated == 1) { "Unable to supersede previous deployment $oldId" }
        }
        val updated = Deployments.update({ (Deployments.id eq id) and (Deployments.status eq DeploymentStatus.HEALTH_CHECKING) }) {
            it[status] = DeploymentStatus.ACTIVE
            it[activeAt] = now
            it[Deployments.replacesDeploymentId] = previousId
            it[updatedAt] = now
        }
        check(updated == 1) { "Unable to activate deployment $id" }
        if (environment == "production") {
            AuditLog.insert {
                it[AuditLog.projectId] = projectId
                it[AuditLog.action] = "deployment_activated"
                it[AuditLog.actor] = "deployment-worker"
                it[AuditLog.reason] = "deployment=$id previous=${previousId ?: "none"}"
            }
        }
        true
    }

    /** Updates canonical deployment state through the centralized transition graph. */
    fun transition(
        id: UUID,
        target: DeploymentStatus,
        failureReason: String? = null,
        replacesDeploymentId: UUID? = null,
        rolledBackToDeploymentId: UUID? = null
    ): Boolean = transaction {
        val row = Deployments.selectAll().where { Deployments.id eq id }.singleOrNull() ?: return@transaction false
        val current = row[Deployments.status]
        if (current == target) return@transaction true
        require(target in allowedTransitions.getValue(current)) {
            "Invalid deployment transition ${current.value} -> ${target.value} for $id"
        }
        val now = LocalDateTime.now()
        var effectiveReplacementId = replacesDeploymentId
        if (target == DeploymentStatus.ACTIVE) {
            val serviceId = row[Deployments.serviceId] ?: error("Deployment has no service_id")
            run {
                val environment = row[Deployments.environment]
                val prior = Deployments.selectAll().where {
                    (Deployments.serviceId eq serviceId) and
                        (Deployments.environment eq environment) and
                        (Deployments.status eq DeploymentStatus.ACTIVE)
                }.singleOrNull()
                if (prior != null) {
                    val priorId = prior[Deployments.id]
                    require(DeploymentStatus.SUPERSEDED in allowedTransitions.getValue(prior[Deployments.status]))
                    val superseded = Deployments.update({
                        (Deployments.id eq priorId) and (Deployments.status eq DeploymentStatus.ACTIVE)
                    }) {
                        it[status] = DeploymentStatus.SUPERSEDED
                        it[supersededAt] = now
                        it[updatedAt] = now
                    }
                    check(superseded == 1) { "Active deployment changed concurrently for service $serviceId/$environment" }
                    effectiveReplacementId = effectiveReplacementId ?: priorId
                }
            }
        }
        val changed = Deployments.update({ (Deployments.id eq id) and (Deployments.status eq current) }) {
            it[status] = target
            it[updatedAt] = now
            when (target) {
                DeploymentStatus.QUEUED -> it[queuedAt] = now
                DeploymentStatus.BUILDING -> it[buildingAt] = now
                DeploymentStatus.STARTING -> it[startingAt] = now
                DeploymentStatus.HEALTH_CHECKING -> it[healthCheckingAt] = now
                DeploymentStatus.ACTIVE -> {
                    it[activeAt] = now
                    effectiveReplacementId?.let { value -> it[Deployments.replacesDeploymentId] = value }
                }
                DeploymentStatus.SUPERSEDED -> it[supersededAt] = now
                DeploymentStatus.FAILED -> {
                    it[failedAt] = now
                    it[Deployments.failureReason] = failureReason
                }
                DeploymentStatus.CANCELLED -> it[cancelledAt] = now
                DeploymentStatus.ROLLED_BACK -> {
                    it[rolledBackAt] = now
                    rolledBackToDeploymentId?.let { value -> it[Deployments.rolledBackToDeploymentId] = value }
                }
            }
        }
        check(changed == 1) { "Deployment state changed concurrently for $id" }
        true
    }
}

internal fun rollbackImageReference(registry: String, imageName: String, imageDigest: String?, imageTag: String): String {
    val registryPrefix = registry.trim().trimEnd('/').takeUnless { it.isBlank() || it == "docker.io" }
        ?.let { "$it/" }.orEmpty()
    val digest = imageDigest?.trim()?.takeIf(String::isNotBlank)
    val immutableReference = digest?.let { value ->
        if ('@' in value) value else "$registryPrefix$imageName@$value"
    }
    return immutableReference ?: "$registryPrefix$imageName:$imageTag"
}
