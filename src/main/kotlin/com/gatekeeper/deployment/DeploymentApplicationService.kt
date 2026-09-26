package com.gatekeeper.deployment

import com.gatekeeper.db.tables.Deployments
import com.gatekeeper.db.tables.DeploymentStatus
import com.gatekeeper.db.tables.Projects
import com.gatekeeper.db.tables.AuditLog
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
        projectId: UUID?,
        configurationId: UUID,
        executionId: UUID,
        triggerSource: String,
        environment: String = "production",
        rolledBackToDeploymentId: UUID? = null
    ) = transaction {
        val now = LocalDateTime.now()
        Deployments.insert {
            it[Deployments.id] = id
            it[Deployments.projectId] = projectId
            it[Deployments.environment] = environment
            it[Deployments.configurationId] = configurationId
            it[Deployments.executionId] = executionId
            it[status] = DeploymentStatus.QUEUED
            it[Deployments.triggerSource] = triggerSource
            it[Deployments.rolledBackToDeploymentId] = rolledBackToDeploymentId
            it[queuedAt] = now
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    fun recordCandidateRuntime(id: UUID, containerName: String, hostPort: Int?, portMappings: Map<Int, Int> = emptyMap()): Boolean = transaction {
        require(containerName.isNotBlank()) { "Candidate container name must not be blank" }
        require(hostPort == null || hostPort in 1..65535) { "Candidate host port must be between 1 and 65535" }
        require(portMappings.all { (containerPort, mappedHostPort) -> containerPort in 1..65535 && mappedHostPort in 1..65535 }) { "Candidate port mapping is invalid" }
        Deployments.update({ (Deployments.id eq id) and (Deployments.status eq DeploymentStatus.HEALTH_CHECKING) }) {
            it[Deployments.runtimeContainerName] = containerName
            it[Deployments.runtimeHostPort] = hostPort
            it[Deployments.runtimePortsJson] = kotlinx.serialization.json.Json.encodeToString(portMappings.mapKeys { it.key.toString() })
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
        val projectId: UUID?, val projectSlug: String?, val name: String?, val hostPort: Int?,
        val ports: Map<Int, Int>, val status: DeploymentStatus
    )

    fun candidateRuntime(id: UUID): CandidateRuntime? = transaction {
        val deployment = Deployments.selectAll().where { Deployments.id eq id }.singleOrNull() ?: return@transaction null
        val projectId = deployment[Deployments.projectId]
        val slug = projectId?.let { owner ->
            Projects.selectAll().where { Projects.id eq owner }.singleOrNull()?.get(Projects.slug)
        }
        val ports = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<Map<String, Int>>(deployment[Deployments.runtimePortsJson]).mapKeys { it.key.toInt() }
        }.getOrDefault(emptyMap())
        CandidateRuntime(projectId, slug, deployment[Deployments.runtimeContainerName], deployment[Deployments.runtimeHostPort], ports, deployment[Deployments.status])
    }

    data class ActiveRuntime(
        val id: UUID, val name: String?, val hostPort: Int?, val image: String?, val hostConfigPort: Int?,
        val containerPort: Int?, val network: String, val restartPolicy: String,
        val env: Map<String, String>, val secretEnv: Map<String, String>, val volumes: List<com.gatekeeper.docker.VolumeMount>
    )

    fun activeRuntime(projectId: UUID?, environment: String): ActiveRuntime? = transaction {
        if (projectId == null) return@transaction null
        Deployments.selectAll().where {
            (Deployments.projectId eq projectId) and (Deployments.environment eq environment) and (Deployments.status eq DeploymentStatus.ACTIVE)
        }.singleOrNull()?.let { row ->
            val execution = com.gatekeeper.db.tables.DeploymentExecutions.selectAll()
                .where { com.gatekeeper.db.tables.DeploymentExecutions.id eq row[Deployments.executionId] }.singleOrNull()
            val image = execution?.let { "${it[com.gatekeeper.db.tables.DeploymentExecutions.imageName]}:${it[com.gatekeeper.db.tables.DeploymentExecutions.imageTag]}" }
            val env = execution?.let { kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(it[com.gatekeeper.db.tables.DeploymentExecutions.envJson]) }.orEmpty()
            val secrets = execution?.get(com.gatekeeper.db.tables.DeploymentExecutions.secretEnvEncrypted)?.let { encoded ->
                kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(com.gatekeeper.security.SecretValueCipher.decrypt(encoded))
            }.orEmpty()
            val volumes = execution?.let { kotlinx.serialization.json.Json.decodeFromString<List<com.gatekeeper.docker.VolumeMount>>(it[com.gatekeeper.db.tables.DeploymentExecutions.volumesJson]) }.orEmpty()
            ActiveRuntime(
                row[Deployments.id], row[Deployments.runtimeContainerName], row[Deployments.runtimeHostPort], image,
                execution?.get(com.gatekeeper.db.tables.DeploymentExecutions.hostPort),
                execution?.get(com.gatekeeper.db.tables.DeploymentExecutions.containerPort),
                execution?.get(com.gatekeeper.db.tables.DeploymentExecutions.network) ?: "bridge",
                execution?.get(com.gatekeeper.db.tables.DeploymentExecutions.restartPolicy) ?: "unless-stopped",
                env, secrets, volumes
            )
        }
    }

    data class ActiveDeploymentRuntime(
        val id: UUID,
        val projectId: UUID?,
        val environment: String,
        val status: DeploymentStatus,
        val containerName: String?,
        val containerPort: Int?,
        val publishedPorts: Map<Int, Int>
    )

    /** Lightweight active-pointer lookup for gateway resolution; never loads secret environment values. */
    fun activeDeploymentRuntime(projectId: UUID, environment: String): ActiveDeploymentRuntime? = transaction {
        val row = Deployments.selectAll().where {
            (Deployments.projectId eq projectId) and (Deployments.environment eq environment) and
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
            row[Deployments.id], row[Deployments.projectId], row[Deployments.environment], row[Deployments.status],
            row[Deployments.runtimeContainerName], containerPort, publishedPorts
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
        val image = execution[com.gatekeeper.db.tables.DeploymentExecutions.imageDigest]?.let { digest ->
            "${if (registry.isBlank() || registry == "docker.io") "" else "$registry/"}$name@$digest"
        } ?: "${if (registry.isBlank() || registry == "docker.io") "" else "$registry/"}$name:${execution[com.gatekeeper.db.tables.DeploymentExecutions.imageTag]}"
        RollbackArtifact(image, execution[com.gatekeeper.db.tables.DeploymentExecutions.commitSha])
    }

    /** Promote only after the external gateway has accepted the candidate and old runtime cleanup succeeded. */
    fun activateAfterCutover(id: UUID, replacesDeploymentId: UUID?): Boolean = transaction {
        val row = Deployments.selectAll().where { Deployments.id eq id }.singleOrNull() ?: return@transaction false
        check(row[Deployments.status] == DeploymentStatus.HEALTH_CHECKING) { "Deployment $id is not health-checking" }
        val projectId = row[Deployments.projectId]
        val environment = row[Deployments.environment]
        val activeRows = if (projectId == null) emptyList() else Deployments.selectAll().where {
            (Deployments.projectId eq projectId) and (Deployments.environment eq environment) and (Deployments.status eq DeploymentStatus.ACTIVE)
        }.toList()
        check(activeRows.size <= 1) { "Multiple active deployments found for $projectId/$environment" }
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
        if (projectId != null && environment == "production") {
            val runtimeName = row[Deployments.runtimeContainerName]
            val project = Projects.selectAll().where { (Projects.id eq projectId) and Projects.deletedAt.isNull() }.singleOrNull()
            if (runtimeName != null && project != null) {
                Projects.update({ Projects.id eq projectId }) {
                    it[Projects.containerName] = runtimeName
                    it[Projects.updatedAt] = now
                }
                AuditLog.insert {
                    it[AuditLog.projectId] = projectId
                    it[AuditLog.action] = "deployment_activated"
                    it[AuditLog.actor] = "deployment-worker"
                    it[AuditLog.reason] = "deployment=$id previous=${previousId ?: "none"}"
                }
            }
        }
        true
    }

    /** Returns false only for pre-state-machine legacy rows with no canonical record. */
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
            val projectId = row[Deployments.projectId]
            if (projectId != null) {
                val environment = row[Deployments.environment]
                val prior = Deployments.selectAll().where {
                    (Deployments.projectId eq projectId) and
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
                    check(superseded == 1) { "Active deployment changed concurrently for project $projectId/$environment" }
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
