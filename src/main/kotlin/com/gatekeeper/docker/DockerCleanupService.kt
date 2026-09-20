package com.gatekeeper.docker

import com.gatekeeper.config.AppConfig
import com.gatekeeper.db.repositories.AuditRepository

data class DockerCleanupItem(val reference: String, val sizeBytes: Long, val reason: String)
data class DockerCleanupResult(val dryRun: Boolean, val inspected: Int, val removed: List<DockerCleanupItem>, val reclaimedBytes: Long)

/** Reference-aware cleanup. It never invokes docker system prune. */
object DockerCleanupService {
    fun pruneProjectImages(projectImagePrefix: String?, preservedImages: Set<String>, dryRun: Boolean, actor: String): DockerCleanupResult {
        val docker = DockerService(AppConfig.dockerSocket)
        return try {
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
            AuditRepository.write(null, "docker_cleanup", actor, "dryRun=$dryRun removed=${items.size} reclaimedBytes=${result.reclaimedBytes} items=${items.joinToString { it.reference }}")
            result
        } finally { docker.close() }
    }

    private fun sameImage(left: String, right: String): Boolean = left == right ||
        left.substringBefore('@') == right.substringBefore('@')
}
