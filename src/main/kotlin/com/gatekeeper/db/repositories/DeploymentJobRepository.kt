package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.DeploymentJobs
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

data class DeploymentJobRecord(
    val id: UUID, val repository: String, val gitRef: String, val registry: String,
    val imageName: String, val imageTag: String, val containerName: String?, val hostPort: Int?, val containerPort: Int?, val network: String, val restartPolicy: String,
    val status: String, val currentStep: String,
    val logs: String, val commitSha: String?, val imageDigest: String?, val errorMessage: String?,
    val createdAt: LocalDateTime, val startedAt: LocalDateTime?, val completedAt: LocalDateTime?, val updatedAt: LocalDateTime
)

object DeploymentJobRepository {
    fun list(limit: Int, offset: Int): List<DeploymentJobRecord> = transaction {
        DeploymentJobs.selectAll().orderBy(DeploymentJobs.createdAt to SortOrder.DESC).limit(limit, offset.toLong()).map { it.toRecord() }
    }

    fun cancel(id: UUID): Boolean = transaction {
        DeploymentJobs.update({ (DeploymentJobs.id eq id) and (DeploymentJobs.status inList listOf("queued", "running", "awaiting_build", "awaiting_container")) }) {
            it[status] = "cancelled"; it[currentStep] = "cancelled"; it[completedAt] = LocalDateTime.now(); it[updatedAt] = LocalDateTime.now()
        } > 0
    }

    fun retry(id: UUID): Boolean = transaction {
        DeploymentJobs.update({ DeploymentJobs.id eq id and (DeploymentJobs.status inList listOf("failed", "cancelled")) }) {
            it[status] = "queued"; it[currentStep] = "queued"; it[errorMessage] = null; it[completedAt] = null; it[updatedAt] = LocalDateTime.now()
        } > 0
    }
    fun create(request: com.gatekeeper.deployment.CreateDeploymentRequest): UUID = transaction {
        val id = UUID.randomUUID()
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
            it[status] = "queued"
            it[currentStep] = "queued"
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
        find(row[DeploymentJobs.id])
    }

    fun update(id: UUID, step: String? = null, log: String? = null, commitSha: String? = null, status: String? = null, error: String? = null) = transaction {
        val existing = DeploymentJobs.selectAll().where { DeploymentJobs.id eq id }.singleOrNull() ?: return@transaction
        DeploymentJobs.update({ DeploymentJobs.id eq id }) {
            step?.let { value -> it[currentStep] = value }
            log?.let { value -> it[logs] = existing[DeploymentJobs.logs] + value + "\n" }
            commitSha?.let { value -> it[DeploymentJobs.commitSha] = value }
            status?.let { value -> it[DeploymentJobs.status] = value }
            error?.let { value -> it[errorMessage] = value }
            if (status == "succeeded" || status == "failed") it[completedAt] = LocalDateTime.now()
            it[updatedAt] = LocalDateTime.now()
        }
    }

    private fun ResultRow.toRecord() = DeploymentJobRecord(
        this[DeploymentJobs.id], this[DeploymentJobs.repository], this[DeploymentJobs.gitRef], this[DeploymentJobs.registry],
        this[DeploymentJobs.imageName], this[DeploymentJobs.imageTag], this[DeploymentJobs.containerName], this[DeploymentJobs.hostPort], this[DeploymentJobs.containerPort], this[DeploymentJobs.network], this[DeploymentJobs.restartPolicy], this[DeploymentJobs.status], this[DeploymentJobs.currentStep],
        this[DeploymentJobs.logs], this[DeploymentJobs.commitSha], this[DeploymentJobs.imageDigest], this[DeploymentJobs.errorMessage],
        this[DeploymentJobs.createdAt], this[DeploymentJobs.startedAt], this[DeploymentJobs.completedAt], this[DeploymentJobs.updatedAt]
    )
}
