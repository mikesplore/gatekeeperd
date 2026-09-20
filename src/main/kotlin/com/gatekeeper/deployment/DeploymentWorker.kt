package com.gatekeeper.deployment

import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.config.AppConfig
import com.gatekeeper.docker.CreateContainerRequest
import com.gatekeeper.docker.DockerService
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
            runCommand(job.id, workspace, listOf("git", "clone", "--depth", "1", "--branch", job.gitRef, "https://github.com/${job.repository}.git", workspace.toString()), authenticated = true)
            val commit = commandOutput(workspace, listOf("git", "rev-parse", "HEAD")).trim()
            DeploymentJobRepository.update(job.id, "checked_out", "Repository checked out", commitSha = commit)
            val image = "${job.imageName}:${job.imageTag}"
            DeploymentJobRepository.update(job.id, "building", "Building $image")
            runCommand(job.id, workspace, listOf("docker", "build", "--tag", image, workspace.toString()))
            DeploymentJobRepository.update(job.id, "pushing", "Pushing $image")
            runCommand(job.id, workspace, listOf("docker", "push", image))
            DeploymentJobRepository.update(job.id, "pulling", "Pulling $image on the deployment host")
            runCommand(job.id, workspace, listOf("docker", "pull", image))
            DeploymentJobRepository.update(job.id, "starting_container", "Starting application container")
            val docker = DockerService(AppConfig.dockerSocket)
            try {
                val existing = job.containerName?.let { docker.getContainer(it) }
                if (existing != null) docker.deleteContainer(existing.name)
                val ports = if (job.hostPort != null && job.containerPort != null) mapOf(job.hostPort to job.containerPort) else emptyMap()
                docker.createContainer(CreateContainerRequest(
                    name = job.containerName ?: "deployment-${job.id.toString().take(8)}",
                    image = image,
                    ports = ports,
                    network = job.network,
                    restartPolicy = job.restartPolicy,
                    pullImage = false
                ))
            } finally { docker.close() }
            DeploymentJobRepository.update(job.id, "running_container", "Container started successfully", status = "succeeded")
        } catch (error: Exception) {
            DeploymentJobRepository.update(job.id, "failed", error.message ?: "Deployment failed", status = "failed", error = error.message ?: "Deployment failed")
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun runCommand(id: java.util.UUID, directory: Path, command: List<String>, authenticated: Boolean = false) {
        val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        if (authenticated && AppConfig.githubToken.isNotBlank()) {
            builder.environment()["GIT_CONFIG_COUNT"] = "1"
            builder.environment()["GIT_CONFIG_KEY_0"] = "http.extraheader"
            builder.environment()["GIT_CONFIG_VALUE_0"] = "AUTHORIZATION: bearer ${AppConfig.githubToken}"
        }
        val process = builder.start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { DeploymentJobRepository.update(id, log = it) } }
        if (process.waitFor() != 0) error("Command failed: ${command.first()}")
    }

    private fun commandOutput(directory: Path, command: List<String>): String = ProcessBuilder(command).directory(directory.toFile()).start().let { process ->
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) error("Unable to read checked-out commit")
        output
    }
}
