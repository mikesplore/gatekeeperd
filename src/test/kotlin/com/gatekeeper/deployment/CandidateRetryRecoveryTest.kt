package com.gatekeeper.deployment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CandidateRetryRecoveryTest {
    private enum class CrashPoint { BEFORE_CREATE, AFTER_CREATE, AFTER_START, AFTER_PERSISTENCE }
    private enum class ContainerState { CREATED, RUNNING }

    @Test
    fun `retry removes deterministic candidate from every worker crash window`() {
        val candidateName = "api-12345678"

        CrashPoint.entries.forEach { crashPoint ->
            val docker = FakeCandidateContainerBoundary()
            var recordedName: String? = null
            when (crashPoint) {
                CrashPoint.BEFORE_CREATE -> Unit
                CrashPoint.AFTER_CREATE -> docker.containers[candidateName] = ContainerState.CREATED
                CrashPoint.AFTER_START -> docker.containers[candidateName] = ContainerState.RUNNING
                CrashPoint.AFTER_PERSISTENCE -> {
                    docker.containers[candidateName] = ContainerState.RUNNING
                    recordedName = candidateName
                }
            }
            val originalRecordedName = recordedName
            var cleared = false

            CandidateRetryRecovery.prepareForRetry(
                deterministicName = candidateName,
                recordedName = recordedName,
                containers = docker,
                clearRecordedRuntime = {
                    cleared = true
                    recordedName = null
                    true
                }
            )

            assertEquals(originalRecordedName != null, cleared, "record clearing at $crashPoint")
            assertNull(recordedName, "record after retry preparation at $crashPoint")
            assertFalse(candidateName in docker.containers, "candidate remains at $crashPoint")

            // The next attempt can use the deterministic name; it cannot fail with
            // Docker's duplicate-name error.
            docker.create(candidateName)
            assertEquals(ContainerState.CREATED, docker.containers[candidateName])
        }
    }

    @Test
    fun `failed candidate removal prevents retry from clearing state or recreating name`() {
        val candidateName = "api-12345678"
        val docker = FakeCandidateContainerBoundary().apply {
            containers[candidateName] = ContainerState.RUNNING
            failRemoval = true
        }
        var clearCalled = false

        assertFailsWith<IllegalStateException> {
            CandidateRetryRecovery.prepareForRetry(
                deterministicName = candidateName,
                recordedName = null,
                containers = docker,
                clearRecordedRuntime = { clearCalled = true; true }
            )
        }

        assertFalse(clearCalled)
        assertEquals(ContainerState.RUNNING, docker.containers[candidateName])
        assertTrue(docker.createAttempts.isEmpty())
    }

    private class FakeCandidateContainerBoundary : CandidateContainerBoundary {
        val containers = mutableMapOf<String, ContainerState>()
        val createAttempts = mutableListOf<String>()
        var failRemoval = false

        override fun removeIfPresent(name: String) {
            if (failRemoval) error("injected Docker removal failure")
            containers.remove(name)
        }

        fun create(name: String) {
            createAttempts += name
            check(name !in containers) { "container name already exists: $name" }
            containers[name] = ContainerState.CREATED
        }
    }
}
