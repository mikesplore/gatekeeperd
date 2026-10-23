package com.gatekeeper.infrastructure

import com.gatekeeper.core.ports.ProcessResult
import com.gatekeeper.testing.FakeCutoverProbe
import com.gatekeeper.testing.FakeClock
import com.gatekeeper.testing.FakeDockerGateway
import com.gatekeeper.testing.FakeFileStore
import com.gatekeeper.testing.FakePortProbe
import com.gatekeeper.testing.FakeProcessRunner
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InfrastructureFakesTest {
    @Test
    fun `process runner fake records commands and returns configured result`() {
        val expected = ProcessResult(7, "out", "err")
        val fake = FakeProcessRunner(expected)

        assertEquals(expected, fake.run(listOf("example", "--flag"), stdin = "input"))
        assertEquals(listOf("example", "--flag"), fake.calls.single().command)
        assertEquals("input", fake.calls.single().stdin)
    }

    @Test
    fun `port probe fake records checks and returns configured reachability`() {
        val fake = FakePortProbe(reachable = false)

        assertFalse(fake.isReachable("127.0.0.1", 8080, 50))
        assertEquals(FakePortProbe.Call("127.0.0.1", 8080, 50), fake.calls.single())
    }

    @Test
    fun `cutover probe fake records both probe paths`() {
        val fake = FakeCutoverProbe(throughNginxResult = false, candidateResult = true)

        assertFalse(fake.throughLocalNginx("example.test", tls = true))
        assertTrue(fake.candidate(8080, "example.test", "/health"))
        assertEquals(listOf("example.test" to true), fake.nginxDomains)
        assertEquals(listOf(Triple(8080, "example.test", "/health")), fake.candidates)
    }

    @Test
    fun `clock and file store fakes are deterministic and in memory`() {
        val clock = FakeClock(Instant.parse("2026-01-01T00:00:00Z"))
        clock.advanceMillis(1_500)
        assertEquals(Instant.parse("2026-01-01T00:00:01.500Z"), clock.now())

        val files = FakeFileStore()
        val path = Path.of("/nginx/site.conf")
        files.write(path, "server {}")
        assertTrue(files.exists(path))
        assertEquals("server {}", files.read(path))
        files.delete(path)
        assertFalse(files.exists(path))
    }

    @Test
    fun `docker gateway fake can close without a daemon`() {
        val docker = FakeDockerGateway()

        assertTrue(docker.available)
        docker.close()
        assertTrue(docker.closed)
    }
}
