package com.gatekeeper.deployment

import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.config.AppConfig
import com.gatekeeper.docker.CreateContainerRequest
import com.gatekeeper.docker.DockerService
import com.gatekeeper.integrations.GitHubAppClient
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

    private suspend fun processNext() {
        val job = DeploymentJobRepository.claimNext() ?: return
        val workspace = Files.createTempDirectory("gatekeeper-deployment-${job.id}-")
        try {
            DeploymentJobRepository.update(job.id, "cloning", "Cloning ${job.repository}@${job.gitRef}")
            val githubToken = if (GitHubAppClient.isConfigured()) GitHubAppClient.installationToken() else AppConfig.githubToken
            runCommand(job.id, workspace, listOf("git", "clone", "--depth", "1", "--branch", job.gitRef, "https://github.com/${job.repository}.git", workspace.toString()), githubToken)
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
                val targetName = job.containerName ?: "deployment-${job.id.toString().take(8)}"
                val existing = job.containerName?.let { docker.getContainer(it) }
                val candidateName = "${targetName}-${job.id.toString().take(8)}"
                val ports = if (job.hostPort != null && job.containerPort != null) mapOf(job.hostPort to job.containerPort) else emptyMap()
                docker.createContainer(CreateContainerRequest(
                    name = candidateName,
                    image = image,
                    ports = ports,
                    network = job.network,
                    restartPolicy = job.restartPolicy,
                    pullImage = false
                ))
                if (docker.containerHealth(candidateName) != "running") {
                    docker.deleteContainer(candidateName)
                    error("Replacement container did not reach running state")
                }
                existing?.let { docker.deleteContainer(it.name) }
                docker.renameContainer(candidateName, targetName)
            } finally { docker.close() }
            DeploymentJobRepository.update(job.id, "running_container", "Container started successfully", status = "succeeded")
        } catch (error: Exception) {
            DeploymentJobRepository.update(job.id, "failed", error.message ?: "Deployment failed", status = "failed", error = error.message ?: "Deployment failed")
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun runCommand(id: java.util.UUID, directory: Path, command: List<String>, githubToken: String = "") {
        val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        if (githubToken.isNotBlank()) {
            builder.environment()["GIT_CONFIG_COUNT"] = "1"
            builder.environment()["GIT_CONFIG_KEY_0"] = "http.extraheader"
            builder.environment()["GIT_CONFIG_VALUE_0"] = "AUTHORIZATION: bearer $githubToken"
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
