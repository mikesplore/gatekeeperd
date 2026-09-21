package com.gatekeeper.docker

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ContainerCreationJob(
    val id: UUID,
    val name: String,
    val status: String,
    val error: String? = null,
    val createdAt: Instant = Instant.now()
)

object ContainerCreationTracker {
    private val jobs = ConcurrentHashMap<UUID, ContainerCreationJob>()

    fun create(name: String): ContainerCreationJob {
        val job = ContainerCreationJob(UUID.randomUUID(), name, "queued")
        jobs[job.id] = job
        return job
    }

    fun update(id: UUID, status: String, error: String? = null) {
        jobs.computeIfPresent(id) { _, job -> job.copy(status = status, error = error) }
    }

    fun find(id: UUID): ContainerCreationJob? = jobs[id]
}
