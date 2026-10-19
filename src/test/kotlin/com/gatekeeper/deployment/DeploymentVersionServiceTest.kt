package com.gatekeeper.deployment

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeploymentVersionServiceTest {
    private val directory: Path = Files.createTempDirectory("deployment-version-test")

    private val running = "1".repeat(40)
    private val latest = "2".repeat(40)
    private val now = Instant.parse("2026-10-14T10:00:00Z")

    @Test
    fun `same recent commit is up to date`() {
        write("source-commit.txt", running)
        write("latest-successful-build.txt", "$running\n${now.minusSeconds(60)}")

        val result = DeploymentVersionService(directory, { now }).current()

        assertEquals("up_to_date", result.status)
        assertTrue(result.upToDate == true)
    }

    @Test
    fun `different recent commit reports update available`() {
        write("source-commit.txt", running)
        write("latest-successful-build.txt", "$latest\n${now.minusSeconds(60)}")

        val result = DeploymentVersionService(directory, { now }).current()

        assertEquals("update_available", result.status)
        assertEquals(false, result.upToDate)
    }

    @Test
    fun `failed latest deployment is reported`() {
        write("source-commit.txt", running)
        write("latest-successful-build.txt", "$latest\n${now.minusSeconds(60)}")
        write("auto-deploy-failed-commit.txt", latest)

        assertEquals("deployment_failed", DeploymentVersionService(directory, { now }).current().status)
    }

    @Test
    fun `held latest deployment is reported after rollback`() {
        write("source-commit.txt", running)
        write("latest-successful-build.txt", "$latest\n${now.minusSeconds(60)}")
        write("auto-deploy.hold", latest)

        val result = DeploymentVersionService(directory, { now }).current()

        assertEquals("deployment_failed", result.status)
        assertEquals(false, result.upToDate)
    }

    @Test
    fun `old check is stale`() {
        write("source-commit.txt", running)
        write("latest-successful-build.txt", "$latest\n${now.minusSeconds(901)}")

        assertEquals("check_stale", DeploymentVersionService(directory, { now }).current().status)
    }

    @Test
    fun `missing or invalid metadata is unknown`() {
        write("source-commit.txt", "not-a-commit")

        val result = DeploymentVersionService(directory, { now }).current()

        assertEquals("unknown", result.status)
        assertNull(result.upToDate)
    }

    private fun write(name: String, contents: String) {
        Files.writeString(directory.resolve(name), contents)
    }
}
