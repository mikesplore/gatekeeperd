package com.gatekeeper.docker

import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import kotlin.test.Test
import kotlin.test.assertEquals

class DockerResourceCleanupTest {
    @Test
    fun `clears only detached volumes and networks`() {
        val docker = FakeDocker()
        val service = DockerCleanupService(docker, NoopAuditRepository, setOf("gk-internal"))

        val volumeResult = service.clearUnusedVolumes("admin")
        val networkResult = service.clearUnusedNetworks("admin")

        assertEquals(listOf("unused-volume"), volumeResult.removed)
        assertEquals(1, volumeResult.skippedInUse)
        assertEquals(listOf("unused-network"), networkResult.removed)
        assertEquals(1, networkResult.skippedInUse)
        assertEquals(2, networkResult.protectedCount)
        assertEquals(listOf("volume:unused-volume", "network:unused-network"), docker.deleted)
    }

    private class FakeDocker : DockerGateway {
        val deleted = mutableListOf<String>()
        override fun listVolumes() = listOf(
            VolumeInfo("unused-volume", "local", "/unused", "local"),
            VolumeInfo("attached-volume", "local", "/attached", "local", containers = listOf("stopped-app"))
        )
        override fun listNetworks() = listOf(
            NetworkInfo("bridge-id", "bridge", "bridge", "local"),
            NetworkInfo("internal-id", "gk-internal", "bridge", "local"),
            NetworkInfo("app-id", "unused-network", "bridge", "local"),
            NetworkInfo("attached-id", "attached-network", "bridge", "local", containers = listOf("app"))
        )
        override fun deleteVolume(name: String) { deleted += "volume:$name" }
        override fun deleteNetwork(name: String) { deleted += "network:$name" }
        override fun containerHealth(containerNameOrId: String) = "running"
        override fun dockerHealthStatus(containerNameOrId: String) = null
        override fun close() = Unit
    }

    private object NoopAuditRepository : AuditRepository {
        override fun write(projectId: java.util.UUID?, action: String, actor: String, reason: String?) = Unit
        override fun findByProjectId(projectId: java.util.UUID) = emptyList<AuditRepository.Record>()
        override fun findAll(limit: Int) = emptyList<AuditRepository.Record>()
        override fun count(query: String?, action: String?) = 0L
        override fun findPage(limit: Int, offset: Int, query: String?, action: String?, sort: String, direction: AuditRepository.SortDirection) = emptyList<AuditRepository.Record>()
        override fun findFiltered(action: String?, actor: String?, limit: Int) = emptyList<AuditRepository.Record>()
    }
}
