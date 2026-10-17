package com.gatekeeper.deployment

import com.gatekeeper.core.ports.HttpPortProbe
import com.gatekeeper.core.ports.PortProbe
import com.gatekeeper.db.repositories.DeploymentJobRecord
import com.gatekeeper.docker.DockerGateway
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadinessCheckerTest {
    @Test
    fun `http probe resolves the configured container port and accepts 4xx response`() {
        val checked = mutableListOf<Triple<String, Int, String>>()
        val checker = ReadinessChecker(
            PortProbe { _, _, _ -> error("TCP probe should not be used") },
            HttpPortProbe { host, port, path, _ -> checked += Triple(host, port, path); true }
        )
        val job = job(readinessType = "http", readinessTarget = "/ready", containerPort = 8080)

        val result = checker.check(HealthyDocker, "candidate", mapOf(8080 to 18080), job)

        assertEquals(ReadinessResult(8080), result)
        assertEquals(listOf(Triple("127.0.0.1", 18080, "/ready")), checked)
    }

    @Test
    fun `docker health probe waits until container reports healthy`() {
        val checker = ReadinessChecker(
            PortProbe { _, _, _ -> true },
            HttpPortProbe { _, _, _, _ -> true }
        )

        assertNull(checker.check(HealthyDocker.copy(health = "starting"), "candidate", emptyMap(), job(readinessType = "docker")))
        assertEquals(ReadinessResult(null), checker.check(HealthyDocker, "candidate", emptyMap(), job(readinessType = "docker")))
    }

    @Test
    fun `unknown readiness mode selects tcp only when a published port exists`() {
        val checker = ReadinessChecker(PortProbe { _, _, _ -> true }, HttpPortProbe { _, _, _, _ -> true })

        assertEquals("tcp (auto-detected)", checker.selectedProbe(job(), true))
        assertEquals("process", checker.selectedProbe(job(), false))
    }

    private fun job(
        readinessType: String? = null,
        readinessTarget: String? = null,
        containerPort: Int? = null
    ) = DeploymentJobRecord(
        id = UUID.randomUUID(), repository = null, gitRef = "main", registry = "docker.io",
        imageName = "example/app", imageTag = "latest", hostPort = null, containerPort = containerPort,
        network = "bridge", restartPolicy = "unless-stopped", env = emptyMap(), secretEnv = emptyMap(),
        volumes = emptyList(), createNetworkIfMissing = false, status = "running", currentStep = "starting",
        logs = "", commitSha = null, imageDigest = null, errorMessage = null, createdAt = LocalDateTime.now(),
        startedAt = null, completedAt = null, updatedAt = LocalDateTime.now(), projectId = UUID.randomUUID(),
        triggerSource = "manual", readinessType = readinessType, readinessTarget = readinessTarget,
        serviceId = UUID.randomUUID()
    )

    private data class FakeDocker(val health: String = "healthy") : DockerGateway {
        override fun containerHealth(containerNameOrId: String) = "running"
        override fun dockerHealthStatus(containerNameOrId: String) = health
        override fun close() = Unit
    }

    private val HealthyDocker = FakeDocker()
}
