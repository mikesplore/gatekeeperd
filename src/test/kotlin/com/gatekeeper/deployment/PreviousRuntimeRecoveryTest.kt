package com.gatekeeper.deployment

import com.gatekeeper.docker.VolumeMount
import com.gatekeeper.docker.ContainerRuntimeOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PreviousRuntimeRecoveryTest {
    private class FakeDocker : RuntimeRestoreDockerBoundary {
        val containers = linkedMapOf<String, RuntimeRestorePlan>()
        var failNext = false
        override fun ensureRuntime(plan: RuntimeRestorePlan) {
            if (failNext) {
                failNext = false
                error("Docker failed while restoring super-secret-value")
            }
            containers.putIfAbsent(plan.containerName, plan)
        }
    }

    private class FakeRoute(var config: String = "candidate-route") : RuntimeRestoreRouteBoundary {
        var restores = 0
        override fun restore(previousNginxConfig: String): Boolean {
            restores++
            config = previousNginxConfig
            return true
        }
    }

    private class FakeState : RuntimeRestoreStateBoundary {
        val history = mutableListOf<Pair<String, String?>>()
        override fun update(state: String, error: String?) { history += state to error }
    }

    private fun plan() = RuntimeRestorePlan(
        containerName = "payments-old",
        imageReference = "registry.example/payments@sha256:0123456789abcdef",
        publishedPorts = mapOf(8080 to 48101, 9090 to 48102),
        portHostIps = mapOf(8080 to "127.0.0.1", 9090 to "0.0.0.0"),
        networkMode = "payments-net",
        networkAttachments = listOf("payments-net", "metrics-net"),
        restartPolicy = "unless-stopped",
        environment = mapOf("DATABASE_PASSWORD" to "super-secret-value", "MODE" to "production"),
        volumes = listOf(VolumeMount("payments-data", "/var/lib/payments", readOnly = false, volumeName = "payments-data")),
        runtimeOptions = ContainerRuntimeOptions(
            command = listOf("/app/server", "--serve"), entrypoint = listOf("/entrypoint.sh"),
            workingDirectory = "/app", user = "1001:1001"
        )
    )

    @Test
    fun `activation failure after previous runtime retirement restores runtime and route`() {
        val docker = FakeDocker()
        val route = FakeRoute()
        val state = FakeState()
        var retired = true
        assertFailsWith<IllegalStateException> {
            PreviousRuntimeRecovery.activateOrRestore(
                activate = { error("canonical activation failed") },
                restore = {
                    PreviousRuntimeRecovery.restore(plan(), "previous-route", docker, route, state, listOf("super-secret-value"))
                    retired = false
                }
            )
        }
        assertEquals(false, retired)
        assertEquals("previous-route", route.config)
        assertEquals("payments-old", docker.containers.keys.single())
        assertEquals("restored", state.history.last().first)
    }

    @Test
    fun `restoration uses exact immutable image reference`() {
        val docker = FakeDocker()
        PreviousRuntimeRecovery.restore(plan(), "previous-route", docker, FakeRoute(), FakeState(), emptyList())
        assertEquals("registry.example/payments@sha256:0123456789abcdef", docker.containers.getValue("payments-old").imageReference)
    }

    @Test
    fun `restoration preserves every published port`() {
        val docker = FakeDocker()
        PreviousRuntimeRecovery.restore(plan(), "previous-route", docker, FakeRoute(), FakeState(), emptyList())
        assertEquals(mapOf(8080 to 48101, 9090 to 48102), docker.containers.getValue("payments-old").publishedPorts)
        assertEquals(mapOf(8080 to "127.0.0.1", 9090 to "0.0.0.0"), docker.containers.getValue("payments-old").portHostIps)
    }

    @Test
    fun `restoration preserves network volume environment and restart settings`() {
        val docker = FakeDocker()
        PreviousRuntimeRecovery.restore(plan(), "previous-route", docker, FakeRoute(), FakeState(), listOf("super-secret-value"))
        val restored = docker.containers.getValue("payments-old")
        assertEquals(listOf("payments-net", "metrics-net"), restored.networkAttachments)
        assertEquals("payments-net", restored.networkMode)
        assertEquals("unless-stopped", restored.restartPolicy)
        assertEquals(listOf(VolumeMount("payments-data", "/var/lib/payments", false, "payments-data")), restored.volumes)
        assertEquals("super-secret-value", restored.environment.getValue("DATABASE_PASSWORD"))
        assertEquals("production", restored.environment.getValue("MODE"))
        assertEquals(ContainerRuntimeOptions(listOf("/app/server", "--serve"), listOf("/entrypoint.sh"), "/app", "1001:1001"), restored.runtimeOptions)
        kotlin.test.assertFalse(plan().toString().contains("super-secret-value"))
    }

    @Test
    fun `failed restoration is recorded safely and succeeds on retry`() {
        val docker = FakeDocker().apply { failNext = true }
        val route = FakeRoute()
        val state = FakeState()
        assertFailsWith<PreviousRuntimeRestorationException> {
            PreviousRuntimeRecovery.restore(plan(), "previous-route", docker, route, state, listOf("super-secret-value"))
        }
        assertEquals("restore_failed", state.history.last().first)
        assertEquals(false, state.history.last().second?.contains("super-secret-value"))
        PreviousRuntimeRecovery.restore(plan(), "previous-route", docker, route, state, listOf("super-secret-value"))
        assertEquals("restored", state.history.last().first)
        assertEquals(1, docker.containers.size)
        assertEquals("previous-route", route.config)
    }

    @Test
    fun `repeated recovery reuses one runtime and restores the same route`() {
        val docker = FakeDocker()
        val route = FakeRoute()
        val state = FakeState()
        repeat(3) { PreviousRuntimeRecovery.restore(plan(), "previous-route", docker, route, state, emptyList()) }
        assertEquals(1, docker.containers.size)
        assertEquals("previous-route", route.config)
        assertEquals(3, route.restores)
        assertEquals(3, state.history.count { it.first == "restored" })
    }
}
