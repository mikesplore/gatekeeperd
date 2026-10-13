package com.gatekeeper.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DockerServiceCreationCleanupTest {
    @Test
    fun `failure before create does not touch any container`() {
        val boundary = FakeDockerBoundary().apply { containers["unrelated-id"] = "api-prod" }
        val failure = IllegalStateException("pre-create failed")

        val thrown = assertFailsWith<IllegalStateException> {
            createContainerWithCleanup(
                beforeCreate = { throw failure },
                create = boundary::create,
                start = boundary::start,
                inspect = boundary::inspect,
                remove = boundary::remove
            )
        }

        assertSame(failure, thrown)
        assertEquals(mapOf("unrelated-id" to "api-prod"), boundary.containers)
        assertEquals(0, boundary.createCalls)
        assertEquals(emptyList(), boundary.removedIds)
    }

    @Test
    fun `create failure cannot remove a container when Docker returned no identity`() {
        val boundary = FakeDockerBoundary().apply { containers["other-id"] = "api-candidate" }
        val failure = IllegalStateException("create failed")
        boundary.createFailure = failure

        val thrown = assertFailsWith<IllegalStateException> {
            createContainerWithCleanup(
                create = boundary::create,
                start = boundary::start,
                inspect = boundary::inspect,
                remove = boundary::remove
            )
        }

        assertSame(failure, thrown)
        assertEquals(mapOf("other-id" to "api-candidate"), boundary.containers)
        assertEquals(emptyList(), boundary.removedIds)
    }

    @Test
    fun `startup failure removes only exact created container id`() {
        val boundary = FakeDockerBoundary().apply {
            containers["existing-id"] = "api-candidate"
            startFailure = IllegalStateException("start failed")
        }

        val thrown = assertFailsWith<IllegalStateException> {
            createContainerWithCleanup(
                create = boundary::create,
                start = boundary::start,
                inspect = boundary::inspect,
                remove = boundary::remove
            )
        }

        assertEquals("start failed", thrown.message)
        assertEquals(mapOf("existing-id" to "api-candidate"), boundary.containers)
        assertEquals(listOf("owned-id"), boundary.removedIds)
    }

    @Test
    fun `inspection failure after start removes the created container`() {
        val boundary = FakeDockerBoundary().apply { inspectFailure = IllegalStateException("inspect failed") }

        val thrown = assertFailsWith<IllegalStateException> {
            createContainerWithCleanup(
                create = boundary::create,
                start = boundary::start,
                inspect = boundary::inspect,
                remove = boundary::remove
            )
        }

        assertEquals("inspect failed", thrown.message)
        assertEquals(listOf("owned-id"), boundary.startedIds)
        assertEquals(listOf("owned-id"), boundary.removedIds)
        assertEquals(emptyMap(), boundary.containers)
    }

    @Test
    fun `cleanup failure is suppressed and does not mask original failure`() {
        val boundary = FakeDockerBoundary().apply {
            inspectFailure = IllegalStateException("inspect failed")
            removeFailure = IllegalArgumentException("cleanup failed")
        }

        val thrown = assertFailsWith<IllegalStateException> {
            createContainerWithCleanup(
                create = boundary::create,
                start = boundary::start,
                inspect = boundary::inspect,
                remove = boundary::remove
            )
        }

        assertSame(boundary.inspectFailure, thrown)
        assertEquals("cleanup failed", thrown.suppressed.single().message)
        assertEquals(listOf("owned-id"), boundary.removedIds)
    }

    @Test
    fun `similar named existing container is untouched`() {
        val boundary = FakeDockerBoundary().apply {
            containers["production-id"] = "api-prod"
            containers["candidate-old-id"] = "api-candidate"
            inspectFailure = IllegalStateException("inspection failed")
        }

        assertFailsWith<IllegalStateException> {
            createContainerWithCleanup(
                create = boundary::create,
                start = boundary::start,
                inspect = boundary::inspect,
                remove = boundary::remove
            )
        }

        assertEquals(setOf("production-id", "candidate-old-id"), boundary.containers.keys)
        assertEquals(listOf("owned-id"), boundary.removedIds)
    }

    private class FakeDockerBoundary {
        val containers = linkedMapOf<String, String>()
        val startedIds = mutableListOf<String>()
        val removedIds = mutableListOf<String>()
        var createCalls = 0
        var createFailure: Throwable? = null
        var startFailure: Throwable? = null
        var inspectFailure: Throwable? = null
        var removeFailure: Throwable? = null

        fun create(): String {
            createCalls++
            createFailure?.let { throw it }
            containers["owned-id"] = "api-candidate"
            return "owned-id"
        }

        fun start(id: String) {
            startedIds += id
            startFailure?.let { throw it }
        }

        fun inspect(id: String): String {
            inspectFailure?.let { throw it }
            return id
        }

        fun remove(id: String) {
            removedIds += id
            removeFailure?.let { throw it }
            containers.remove(id)
        }
    }
}
