package com.gatekeeper.deployment

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Reads the local deployment marker files written by deploy.sh and the auto-deploy timer. */
class DeploymentVersionService(
    private val deploymentDirectory: Path = Path.of("/opt/gatekeeperd"),
    private val now: () -> Instant = Instant::now,
    private val maxCheckAge: Duration = Duration.ofMinutes(15),
) {
    fun current(): DeploymentVersionStatus {
        val runningCommit = readCommit(deploymentDirectory.resolve("source-commit.txt"))
        val latestFile = deploymentDirectory.resolve("latest-successful-build.txt")
        val latest = runCatching {
            Files.readAllLines(latestFile).let { lines ->
                val commit = lines.getOrNull(0)?.takeIf(COMMIT_REGEX::matches) ?: return@runCatching null
                val checkedAt = lines.getOrNull(1)?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: return@runCatching null
                commit to checkedAt
            }
        }.getOrNull()

        if (runningCommit == null || latest == null) {
            return DeploymentVersionStatus("unknown", runningCommit, latest?.first, latest?.second?.toString(), null)
        }

        val (latestCommit, checkedAt) = latest
        val failedCommit = readCommit(deploymentDirectory.resolve("auto-deploy-failed-commit.txt"))
        val heldCommit = readCommit(deploymentDirectory.resolve("auto-deploy.hold"))
        val age = Duration.between(checkedAt, now())
        if (age.isNegative || age > maxCheckAge) {
            return DeploymentVersionStatus("check_stale", runningCommit, latestCommit, checkedAt.toString(), null)
        }
        if ((failedCommit == latestCommit || heldCommit == latestCommit) && runningCommit != latestCommit) {
            return DeploymentVersionStatus("deployment_failed", runningCommit, latestCommit, checkedAt.toString(), false)
        }
        return if (runningCommit == latestCommit) {
            DeploymentVersionStatus("up_to_date", runningCommit, latestCommit, checkedAt.toString(), true)
        } else {
            DeploymentVersionStatus("update_available", runningCommit, latestCommit, checkedAt.toString(), false)
        }
    }

    private fun readCommit(path: Path): String? = runCatching {
        Files.readString(path).trim().takeIf(COMMIT_REGEX::matches)
    }.getOrNull()

    private companion object {
        val COMMIT_REGEX = Regex("[0-9a-f]{40}")
    }
}

@Serializable
data class DeploymentVersionStatus(
    val status: String,
    val runningCommit: String?,
    val latestSuccessfulCommit: String?,
    val checkedAt: String?,
    val upToDate: Boolean?,
)
