package com.gatekeeper.deployment

import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.db.repositories.ProjectRepository
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.RegistryCredentialRepository
import com.gatekeeper.config.AppConfig
import com.gatekeeper.docker.CreateContainerRequest
import com.gatekeeper.docker.DockerService
import com.gatekeeper.docker.DockerCleanupService
import com.gatekeeper.integrations.GitHubAppClient
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

object DeploymentWorker {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.deployment.DeploymentWorker")
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            DeploymentJobRepository.recoverStale(AppConfig.deploymentStaleMinutes)
            while (isActive) {
                runCatching { processNext() }.onFailure { logger.error("Deployment worker cycle failed", it) }
                delay(1000)
            }
        }
    }

    private suspend fun processNext() {
        val job = DeploymentJobRepository.claimNext() ?: return
        val workspace = Files.createTempDirectory("gatekeeper-deployment-${job.id}-")
        var candidateName: String? = null
        try {
            ensureNotCancelled(job.id)
            DeploymentJobRepository.update(job.id, "cloning", "Cloning ${job.repository}@${job.gitRef}")
            val githubToken = runCatching { GitHubAppClient.installationToken() }.getOrElse {
                logger.info("GitHub App is not connected; attempting public repository clone for {}", job.repository)
                ""
            }
            val cloneCommand = listOf("git", "clone", "--depth", "1", "--branch", job.gitRef, "https://github.com/${job.repository}.git", workspace.toString())
            try {
                runCommand(job.id, workspace, cloneCommand, githubToken)
            } catch (error: Exception) {
                if (githubToken.isBlank()) throw error
                logger.info("Authenticated clone unavailable for {}; retrying as public repository", job.repository)
                workspace.toFile().deleteRecursively()
                Files.createDirectories(workspace)
                runCommand(job.id, workspace, cloneCommand)
            }
            val commit = commandOutput(workspace, listOf("git", "rev-parse", "HEAD")).trim()
            DeploymentJobRepository.update(job.id, "checked_out", "Repository checked out", commitSha = commit)
            val image = registryImage(job.registry, job.imageName, job.imageTag)
            RegistryCredentialRepository.find(job.registry)?.let { credential ->
                dockerLogin(job.id, job.registry, credential.username, credential.password)
            }
            DeploymentJobRepository.update(job.id, "building", "Building $image")
            runCommand(job.id, workspace, listOf("docker", "build", "--tag", image, workspace.toString()))
            DeploymentJobRepository.update(job.id, "pushing", "Pushing $image")
            runCommand(job.id, workspace, listOf("docker", "push", image))
            DeploymentJobRepository.update(job.id, "pulling", "Pulling $image on the deployment host")
            runCommand(job.id, workspace, listOf("docker", "pull", image))
            DeploymentJobRepository.update(job.id, "starting_container", "Starting application container")
            ensureNotCancelled(job.id)
            val docker = DockerService(AppConfig.dockerSocket)
            try {
                val digest = docker.imageDigest(image)
                DeploymentJobRepository.update(job.id, log = "Image digest: ${digest ?: "unavailable"}", imageDigest = digest)
                // Redeployments must reuse the project's stable container name. A new
                // execution id must not become a new host identity, otherwise the old
                // container keeps the published port and the replacement cannot start.
                val targetName = job.containerName
                    ?: job.projectSlug?.let { ProjectRepository.findBySlug(it)?.containerName }
                    ?: "deployment-${job.id.toString().take(8)}"
                if (job.network != "bridge" && job.createNetworkIfMissing) docker.createNetworkIfMissing(job.network)
                val existing = job.containerName?.let { docker.getContainer(it) }
                    ?: docker.getContainer(targetName)
                DeploymentJobRepository.setPreviousContainer(job.id, existing?.name, existing?.image)
                candidateName = "${targetName}-${job.id.toString().take(8)}"
                val ports = if (job.hostPort != null && job.containerPort != null) mapOf(job.hostPort to job.containerPort) else emptyMap()
                // Published host ports cannot be shared. Stop/remove the old instance
                // immediately before creating its replacement, then restore it through
                // the normal rollback path if the replacement fails.
                existing?.let { docker.stopContainer(it.name); docker.deleteContainer(it.name) }
                docker.createContainer(CreateContainerRequest(
                    name = candidateName,
                    image = image,
                    ports = ports,
                    network = job.network,
                    restartPolicy = job.restartPolicy,
                    env = job.env + job.secretEnv,
                    volumes = job.volumes,
                    pullImage = false
                ))
                if (!awaitHealthy(docker, candidateName, job.hostPort)) {
                    docker.deleteContainer(candidateName)
                    candidateName = null
                    error("Replacement container did not become healthy and reachable")
                }
                docker.renameContainer(candidateName, targetName)
                candidateName = null
            } finally { docker.close() }
            job.projectSlug?.let { ProjectRepository.syncDeployment(it, job.containerName ?: "deployment-${job.id.toString().take(8)}", commit) }
            DockerCleanupService.pruneProjectImages(job.imageName, setOf(image, job.previousImage).filterNotNull().toSet(), false, "deployment-worker")
            AuditRepository.write(null, "deployment_succeeded", "deployment-worker", "job=${job.id} repository=${job.repository} commit=$commit")
            DeploymentJobRepository.update(job.id, "running_container", "Container started successfully", status = "succeeded")
        } catch (error: CancellationException) {
            candidateName?.let { name -> runCatching { val cleanupDocker = DockerService(AppConfig.dockerSocket); cleanupDocker.deleteContainer(name); cleanupDocker.close() } }
            logger.info("Deployment {} cancelled", job.id)
        } catch (error: Exception) {
            candidateName?.let { name -> runCatching { val cleanupDocker = DockerService(AppConfig.dockerSocket); cleanupDocker.deleteContainer(name); cleanupDocker.close() } }
            if (!DeploymentJobRepository.isCancelled(job.id)) {
                AuditRepository.write(null, "deployment_failed", "deployment-worker", "job=${job.id} error=${error.message}")
                DeploymentJobRepository.update(job.id, "failed", error.message ?: "Deployment failed", status = "failed", error = error.message ?: "Deployment failed")
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    fun rollback(id: java.util.UUID): Boolean {
        val job = DeploymentJobRepository.find(id) ?: return false
        val oldImage = job.previousImage ?: return false
        val target = job.containerName ?: return false
        val docker = DockerService(AppConfig.dockerSocket)
        return try {
            val candidate = "$target-rollback-${id.toString().take(8)}"
            if (job.network != "bridge" && job.createNetworkIfMissing) docker.createNetworkIfMissing(job.network)
            docker.createContainer(CreateContainerRequest(name = candidate, image = oldImage, ports = if (job.hostPort != null && job.containerPort != null) mapOf(job.hostPort to job.containerPort) else emptyMap(), network = job.network, restartPolicy = job.restartPolicy, env = job.env + job.secretEnv, volumes = job.volumes, pullImage = true))
            if (!awaitHealthy(docker, candidate, job.hostPort)) { docker.deleteContainer(candidate); return false }
            docker.getContainer(target)?.let { docker.deleteContainer(it.name) }
            docker.renameContainer(candidate, target)
            job.projectSlug?.let { ProjectRepository.syncDeployment(it, target, job.commitSha) }
            AuditRepository.write(null, "deployment_rolled_back", "deployment-worker", "job=$id image=$oldImage")
            true
        } catch (e: Exception) {
            logger.error("Deployment rollback failed for $id", e); false
        } finally { docker.close() }
    }

    private fun ensureNotCancelled(id: java.util.UUID) {
        if (DeploymentJobRepository.isCancelled(id)) throw CancellationException("Deployment cancelled")
    }

    private fun awaitHealthy(docker: DockerService, name: String, hostPort: Int?): Boolean {
        repeat(30) {
            if (docker.containerHealth(name) == "running" && (hostPort == null || tcpReachable(hostPort))) return true
            Thread.sleep(1000)
        }
        return false
    }

    private fun tcpReachable(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500); true }
    }.getOrDefault(false)

    private fun registryImage(registry: String, name: String, tag: String): String {
        val host = registry.trim().trimEnd('/')
        return if (host.isBlank() || host == "docker.io") "$name:$tag" else "$host/$name:$tag"
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

    private fun dockerLogin(id: UUID, registry: String, username: String, password: String) {
        val host = if (registry == "docker.io") "https://index.docker.io/v1/" else registry
        val process = ProcessBuilder("docker", "login", host, "--username", username, "--password-stdin")
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(password); it.newLine() }
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) error("Docker registry authentication failed for $registry: ${output.trim().take(300)}")
        DeploymentJobRepository.update(id, "registry_authenticated", "Authenticated to registry $registry")
    }

    private fun commandOutput(directory: Path, command: List<String>): String = ProcessBuilder(command).directory(directory.toFile()).start().let { process ->
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) error("Unable to read checked-out commit")
        output
    }
}
