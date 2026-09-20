package com.gatekeeper.deployment

import com.gatekeeper.db.repositories.DeploymentJobRepository
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

object DeploymentWorker {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.deployment.DeploymentWorker")
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { processNext() }.onFailure { logger.error("Deployment worker cycle failed", it) }
                delay(1000)
            }
        }
    }

    private fun processNext() {
        val job = DeploymentJobRepository.claimNext() ?: return
        val workspace = Files.createTempDirectory("gatekeeper-deployment-${job.id}-")
        try {
            DeploymentJobRepository.update(job.id, "cloning", "Cloning ${job.repository}@${job.gitRef}")
            runCommand(job.id, workspace, listOf("git", "clone", "--depth", "1", "--branch", job.gitRef, "https://github.com/${job.repository}.git", workspace.toString()))
            val commit = commandOutput(workspace, listOf("git", "rev-parse", "HEAD")).trim()
            DeploymentJobRepository.update(job.id, "checked_out", "Repository checked out", commitSha = commit)
            DeploymentJobRepository.update(job.id, "awaiting_build", "Checkout complete; build stage is next", status = "awaiting_build")
        } catch (error: Exception) {
            DeploymentJobRepository.update(job.id, "failed", error.message ?: "Deployment failed", status = "failed", error = error.message ?: "Deployment failed")
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun runCommand(id: java.util.UUID, directory: Path, command: List<String>) {
        val process = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { DeploymentJobRepository.update(id, log = it) } }
        if (process.waitFor() != 0) error("Command failed: ${command.first()}")
    }

    private fun commandOutput(directory: Path, command: List<String>): String = ProcessBuilder(command).directory(directory.toFile()).start().let { process ->
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) error("Unable to read checked-out commit")
        output
    }
}
