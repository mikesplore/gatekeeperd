package com.gatekeeper.docker

import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import kotlinx.serialization.Serializable

data class DockerCleanupItem(val reference: String, val sizeBytes: Long, val reason: String)
data class DockerCleanupResult(val dryRun: Boolean, val inspected: Int, val removed: List<DockerCleanupItem>, val reclaimedBytes: Long)

@Serializable
data class DockerResourceCleanupResult(
    val resource: String,
    val inspected: Int,
    val removed: List<String>,
    val skippedInUse: Int,
    val protectedCount: Int,
    val failed: List<String>
)

/** Reference-aware cleanup. It never invokes docker system prune. */
class DockerCleanupService(
    private val docker: DockerGateway,
    private val auditRepository: AuditRepository,
    private val protectedNetworks: Set<String> = emptySet()
) {
    fun clearUnusedVolumes(actor: String): DockerResourceCleanupResult {
        val volumes = docker.listVolumes()
        val unused = volumes.filter { it.containers.isEmpty() }
        val removed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        unused.forEach { volume ->
            runCatching { docker.deleteVolume(volume.name) }
                .onSuccess { removed += volume.name }
                .onFailure { failed += volume.name }
        }
        val result = DockerResourceCleanupResult(
            resource = "volumes", inspected = volumes.size, removed = removed,
            skippedInUse = volumes.size - unused.size, protectedCount = 0, failed = failed
        )
        auditRepository.write(null, "docker_volume_cleanup", actor,
            "inspected=${result.inspected} removed=${removed.size} skippedInUse=${result.skippedInUse} failed=${failed.size} names=${removed.joinToString()}")
        return result
    }

    fun clearUnusedNetworks(actor: String): DockerResourceCleanupResult {
        val builtInNetworks = setOf("bridge", "host", "none")
        val networks = docker.listNetworks()
        val protected = networks.filter { it.name in builtInNetworks || it.name in protectedNetworks }
        val candidates = networks.filter { it.name !in builtInNetworks && it.name !in protectedNetworks }
        val unused = candidates.filter { it.containers.isEmpty() && it.endpoints.isEmpty() }
        val removed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        unused.forEach { network ->
            runCatching { docker.deleteNetwork(network.name) }
                .onSuccess { removed += network.name }
                .onFailure { failed += network.name }
        }
        val result = DockerResourceCleanupResult(
            resource = "networks", inspected = networks.size, removed = removed,
            skippedInUse = candidates.size - unused.size, protectedCount = protected.size, failed = failed
        )
        auditRepository.write(null, "docker_network_cleanup", actor,
            "inspected=${result.inspected} removed=${removed.size} skippedInUse=${result.skippedInUse} protected=${result.protectedCount} failed=${failed.size} names=${removed.joinToString()}")
        return result
    }

    fun pruneProjectImages(projectImagePrefix: String?, preservedImages: Set<String>, dryRun: Boolean, actor: String): DockerCleanupResult {
        val referenced = docker.referencedImageNames() + preservedImages
            val candidates = docker.listImages().filter { image ->
                image.tags.isNotEmpty() &&
                    (projectImagePrefix == null || image.tags.any { it.startsWith(projectImagePrefix) }) &&
                    image.tags.none { tag -> referenced.any { ref -> sameImage(tag, ref) } }
            }
            val items = candidates.map { image ->
                DockerCleanupItem(image.tags.joinToString(","), image.sizeBytes, "unreferenced project image")
            }
            if (!dryRun) candidates.forEach { image ->
                image.tags.forEach { tag ->
                    if (referenced.none { ref -> sameImage(tag, ref) }) runCatching { docker.deleteImageReference(tag) }
                }
            }
            val result = DockerCleanupResult(dryRun, docker.listImages().size, items, items.sumOf { it.sizeBytes })
            auditRepository.write(null, "docker_cleanup", actor, "dryRun=$dryRun removed=${items.size} reclaimedBytes=${result.reclaimedBytes} items=${items.joinToString { it.reference }}")
        return result
    }

    private fun sameImage(left: String, right: String): Boolean = left == right ||
        left.substringBefore('@') == right.substringBefore('@')
}
