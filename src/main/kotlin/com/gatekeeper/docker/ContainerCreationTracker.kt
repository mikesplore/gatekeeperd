package com.gatekeeper.docker

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ContainerCreationJob(
    val id: String,
    val name: String,
    val status: String,
    val error: String? = null,
    val createdAt: String = Instant.now().toString()
)

object ContainerCreationTracker {
    private val jobs = ConcurrentHashMap<String, ContainerCreationJob>()

    fun create(name: String): ContainerCreationJob {
        val job = ContainerCreationJob(UUID.randomUUID().toString(), name, "queued")
        jobs[job.id] = job
        return job
    }

    fun update(id: String, status: String, error: String? = null) {
        jobs.computeIfPresent(id) { _, job -> job.copy(status = status, error = error) }
    }

    fun find(id: String): ContainerCreationJob? = jobs[id]
}
