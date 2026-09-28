package com.gatekeeper.deployment

import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.db.repositories.DeploymentJobRecord
import com.gatekeeper.db.repositories.AuditRepository
import com.gatekeeper.db.repositories.RegistryCredentialRepository
import com.gatekeeper.config.AppConfig
import com.gatekeeper.plugins.DistributedLock
import com.gatekeeper.docker.CreateContainerRequest
import com.gatekeeper.docker.DockerService
import com.gatekeeper.docker.DockerCleanupService
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.integrations.GitHubAppClient
import com.gatekeeper.security.SecretValueCipher
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

object DeploymentWorker {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.deployment.DeploymentWorker")
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            DeploymentJobRepository.recoverStale(AppConfig.deploymentStaleMinutes)
            while (isActive) {
                runCatching { processNext() }.onFailure { logger.error("Deployment worker cycle failed", it) }
                delay(1000.milliseconds)
            }
        }
    }

    private suspend fun processNext() {
        val job = DeploymentJobRepository.claimNext() ?: return
        DistributedLock.withLock("deployment-service:${job.serviceId}:${job.environment}") {
            runBlocking { processClaimedJob(job) }
        }
    }

    private suspend fun processClaimedJob(job: DeploymentJobRecord) {
        if (job.currentStep == "readiness_succeeded" || job.currentStep == "cutover_in_progress") {
            val docker = DockerService(AppConfig.dockerSocket)
            var routeRollback: (() -> Boolean)? = null
            try {
                cutover(job, docker) { rollback -> routeRollback = rollback }
            } catch (error: Exception) {
                val routeRestored = routeRollback?.let { runCatching(it).getOrDefault(false) } ?: true
                if (!routeRestored) logger.error("Deployment {} failed after gateway cutover; candidate retained because gateway restoration failed", job.id)
                DeploymentJobRepository.update(job.id, step = "readiness_succeeded", log = "Cutover attempt failed; ready candidate retained for retry: ${error.message}")
                AuditRepository.write(null, "deployment_cutover_failed", "deployment-worker", "job=${job.id} error=${error.message}")
            } finally {
                docker.close()
            }
            return
        }
        val workspace = withContext(Dispatchers.IO) {
            Files.createTempDirectory("gatekeeper-deployment-${job.id}-")
        }
        var candidateName: String? = null
        var routeRollback: (() -> Boolean)? = null
        try {
            DeploymentApplicationService.candidateContainerName(job.id)?.let { staleCandidate ->
                runCatching { val cleanupDocker = DockerService(AppConfig.dockerSocket); try { cleanupDocker.deleteContainer(staleCandidate) } finally { cleanupDocker.close() } }
                DeploymentApplicationService.clearCandidateRuntime(job.id)
            }
            ensureNotCancelled(job.id)
            val rollbackArtifact = DeploymentApplicationService.rollbackArtifact(job.id)
            val commit: String?
            val image: String
            if (rollbackArtifact != null) {
                commit = rollbackArtifact.commitSha
                image = rollbackArtifact.image
                DeploymentJobRepository.update(job.id, "pulling", "Pulling rollback artifact $image", commitSha = commit)
            } else if (job.repository.isNullOrBlank()) {
                commit = null
                image = registryImage(job.registry, job.imageName, job.imageTag)
                DeploymentJobRepository.update(job.id, "pulling", "Using prebuilt registry image $image")
            } else {
                val repository = job.repository
                DeploymentJobRepository.update(job.id, "cloning", "Cloning $repository@${job.gitRef}")
                val githubToken = runCatching { GitHubAppClient.installationToken() }.getOrElse {
                    logger.info("GitHub App is not connected; attempting public repository clone for {}", repository)
                    ""
                }
                val cloneCommand = listOf("git", "clone", "--depth", "1", "--branch", job.gitRef, "https://github.com/$repository.git", workspace.toString())
                try {
                    runCommand(job.id, workspace, cloneCommand, githubToken)
                } catch (error: Exception) {
                    if (githubToken.isBlank()) throw error
                    logger.info("Authenticated clone unavailable for {}; retrying as public repository", repository)
                    workspace.toFile().deleteRecursively()
                    withContext(Dispatchers.IO) {
                        Files.createDirectories(workspace)
                    }
                    runCommand(job.id, workspace, cloneCommand)
                }
                commit = commandOutput(workspace, listOf("git", "rev-parse", "HEAD")).trim()
                DeploymentJobRepository.update(job.id, "checked_out", "Repository checked out", commitSha = commit)
                image = registryImage(job.registry, job.imageName, job.imageTag)
            }
            RegistryCredentialRepository.find(job.registry)?.let { credential ->
                if (credential.credentialId != null && credential.version != null) {
                    DeploymentApplicationService.recordCredentialReference(job.id, credential.credentialId, credential.version)
                }
                dockerLogin(job.id, job.registry, credential.username, credential.password)
            }
            if (rollbackArtifact == null && !job.repository.isNullOrBlank()) {
                DeploymentJobRepository.update(job.id, "building", "Building $image")
                runCommand(job.id, workspace, listOf("docker", "build", "--tag", image, workspace.toString()))
                DeploymentJobRepository.update(job.id, "pushing", "Pushing $image")
                runCommand(job.id, workspace, listOf("docker", "push", image))
            }
            DeploymentJobRepository.update(job.id, "pulling", "Pulling $image on the deployment host")
            runCommand(job.id, workspace, listOf("docker", "pull", image))
            DeploymentJobRepository.update(job.id, "starting_container", "Starting application container")
            ensureNotCancelled(job.id)
            val docker = DockerService(AppConfig.dockerSocket)
            try {
                val digest = docker.imageDigest(image)
                DeploymentJobRepository.update(job.id, log = "Image digest: ${digest ?: "unavailable"}", imageDigest = digest)
                if (job.network != "bridge" && job.createNetworkIfMissing) docker.createNetworkIfMissing(job.network)
                candidateName = "deployment-${job.id}"
                val readinessContainerPort = when (job.readinessType?.lowercase()) {
                    "http" -> job.readinessTarget?.substringBefore('/')?.toIntOrNull() ?: job.containerPort
                    "tcp" -> job.readinessTarget?.toIntOrNull() ?: job.containerPort
                    else -> job.containerPort
                }
                val dynamicContainerPorts = (setOfNotNull(job.containerPort) + setOfNotNull(readinessContainerPort))
                val created = docker.createContainer(CreateContainerRequest(
                    name = candidateName,
                    image = image,
                    randomHostPorts = dynamicContainerPorts,
                    network = job.network,
                    restartPolicy = job.restartPolicy,
                    env = DeploymentJobRepository.resolveEnvironmentForExecution(job.id).values,
                    volumes = job.volumes,
                    pullImage = false
                ))
                val candidatePortMappings = dynamicContainerPorts.associateWith { containerPort ->
                    created.ports.publishedHostPort(containerPort)
                        ?: error("Docker did not assign a host port for candidate container port $containerPort")
                }
                val candidateHostPort = job.containerPort?.let(candidatePortMappings::get)
                    ?: candidatePortMappings[readinessContainerPort]
                val selectedProbe = selectedReadiness(job, candidatePortMappings.isNotEmpty())
                DeploymentJobRepository.update(job.id, step = "health_checking", log = "Waiting for candidate readiness using $selectedProbe probe")
                if (!awaitHealthy(docker, candidateName, candidatePortMappings, job)) {
                    docker.deleteContainer(candidateName)
                    candidateName = null
                    error("Candidate container did not become healthy and reachable")
                }
                ensureNotCancelled(job.id)
                check(DeploymentApplicationService.recordCandidateRuntime(job.id, candidateName, candidateHostPort, candidatePortMappings)) {
                    "Unable to persist candidate runtime for deployment ${job.id}"
                }
                DeploymentJobRepository.update(job.id, step = "readiness_succeeded", log = "Candidate passed readiness checks ($selectedProbe); deployment remains health-checking until cutover")
                AuditRepository.write(null, "deployment_readiness_succeeded", "deployment-worker", "job=${job.id} repository=${job.repository ?: "prebuilt-image"} commit=${commit ?: "not-applicable"}")
                cutover(job, docker) { rollback -> routeRollback = rollback }
                candidateName = null
            } finally { docker.close() }
        } catch (error: CancellationException) {
            candidateName?.let { name -> runCatching { val cleanupDocker = DockerService(AppConfig.dockerSocket); cleanupDocker.deleteContainer(name); cleanupDocker.close() } }
            logger.info("Deployment {} cancelled", job.id)
        } catch (error: Exception) {
            val candidateIsReady = DeploymentJobRepository.find(job.id)?.currentStep in setOf("readiness_succeeded", "cutover_in_progress")
            val routeRestored = routeRollback?.let { rollback -> runCatching(rollback).getOrDefault(false) } ?: true
            if (candidateIsReady && !routeRestored) {
                logger.error("Deployment {} route rollback failed; candidate remains the routed runtime and cutover requires operator review", job.id)
                throw error
            }
            if (candidateIsReady) {
                DeploymentJobRepository.update(job.id, step = "readiness_succeeded", log = "Cutover attempt failed; ready candidate retained for retry: ${error.message}")
            }
            if (routeRestored && !candidateIsReady) candidateName?.let { name -> runCatching { val cleanupDocker = DockerService(AppConfig.dockerSocket); cleanupDocker.deleteContainer(name); cleanupDocker.close() } }
            else logger.error("Deployment {} failed after gateway cutover; candidate runtime retained because gateway restoration failed", job.id)
            if (!DeploymentJobRepository.isCancelled(job.id) && !candidateIsReady) {
                AuditRepository.write(null, "deployment_failed", "deployment-worker", "job=${job.id} error=${error.message}")
                DeploymentJobRepository.update(job.id, "failed", error.message ?: "Deployment failed", status = "failed", error = error.message ?: "Deployment failed")
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun cutover(job: DeploymentJobRecord, docker: DockerService, onRouteRollback: ((() -> Boolean)?) -> Unit) {
        val runtime = DeploymentApplicationService.candidateRuntime(job.id)
            ?: error("Canonical candidate runtime missing for deployment ${job.id}")
        val candidateRuntimeName = requireNotNull(runtime.name) { "Candidate container name is missing" }
        check(docker.containerHealth(candidateRuntimeName) == "running") { "Ready candidate runtime is no longer running" }
        val previousDeployment = DeploymentApplicationService.activeRuntime(job.serviceId, job.environment)
        val previousContainer = previousDeployment?.name
        val changesProductionRoute = job.environment == "production"
        val hasManagedSite = changesProductionRoute && SiteRepository.existsForService(job.serviceId)
        var rollbackRoute: (() -> Boolean)? = null
        if (hasManagedSite) {
            val site = SiteRepository.findByServiceId(job.serviceId) ?: error("Managed site record missing for service ${job.serviceId}")
            val ownerSlug = site.projectSlug ?: error("Managed site nginx slug is unavailable for site ${site.id}")
            val siteService = NginxService()
            val previousConfig = siteService.inspectSite(ownerSlug).content
            val containerPort = site.upstreamExplicitPort ?: job.containerPort
                ?: error("Managed site has no upstream port and deployment has no container port")
            val hostPort = runtime.ports[containerPort]
                ?: if (site.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.EXPLICIT_PORT) runtime.hostPort
                    ?: error("Candidate runtime host port is missing")
                else error("Candidate does not publish managed site container port $containerPort")
            check(siteService.switchDeploymentUpstream(job.serviceId, ownerSlug, "127.0.0.1", containerPort, hostPort)) {
                "Gateway/site upstream cutover failed; previous runtime remains active"
            }
            rollbackRoute = {
                val restored = previousConfig?.let { siteService.restoreSiteConfiguration(ownerSlug, it) } ?: false
                if (restored) SiteRepository.restoreDeploymentUpstreamForSite(site) != null else false
            }
            onRouteRollback(rollbackRoute)
        }
        var previousRetired = false
        if (changesProductionRoute && previousContainer != null && previousContainer != candidateRuntimeName) {
            try {
                docker.stopContainer(previousContainer)
                docker.deleteContainer(previousContainer)
                previousRetired = true
            } catch (error: Exception) {
                val restored = rollbackRoute?.let { runCatching(it).getOrDefault(false) } ?: true
                if (!restored) logger.error("Old runtime retirement failed and gateway restoration also failed; candidate remains routed", error)
                throw error
            }
        }
        try {
            check(DeploymentApplicationService.activateAfterCutover(job.id, previousDeployment?.id)) {
                "Canonical deployment activation failed after cutover"
            }
        } catch (error: Exception) {
            if (previousRetired && previousDeployment?.name != null && previousDeployment.image != null) {
                docker.createContainer(CreateContainerRequest(
                    name = previousDeployment.name,
                    image = previousDeployment.image,
                    ports = if (previousDeployment.hostConfigPort != null && previousDeployment.containerPort != null) mapOf(previousDeployment.hostConfigPort to previousDeployment.containerPort) else emptyMap(),
                    network = previousDeployment.network,
                    restartPolicy = previousDeployment.restartPolicy,
                    env = DeploymentJobRepository.resolveEnvironmentForExecution(previousDeployment.executionId).values,
                    volumes = previousDeployment.volumes,
                    pullImage = true
                ))
            }
            rollbackRoute?.let { runCatching(it).onFailure { rollbackError -> logger.error("Unable to restore previous gateway route", rollbackError) } }
            throw error
        }
        DeploymentJobRepository.update(job.id, step = "active", log = "Gateway switched to candidate; previous runtime retired", status = "succeeded")
        DeploymentApplicationService.rollbackTargetId(job.id)?.let { targetId ->
            AuditRepository.write(null, "deployment_rollback_activated", "deployment-worker", "deployment=${job.id} rolled_back_to=$targetId")
        }
        if (previousContainer != null && previousContainer != candidateRuntimeName) {
            AuditRepository.write(null, "deployment_runtime_superseded", "deployment-worker", "deployment=${previousDeployment.id} container=$previousContainer")
        }
    }

    fun rollback(id: java.util.UUID): UUID? {
        return runCatching { DeploymentJobRepository.createRollback(id) }
            .onFailure { logger.warn("Unable to queue rollback deployment for {}", id, it) }
            .getOrNull()
    }

    fun cancel(id: UUID): Boolean {
        val changed = DeploymentJobRepository.cancel(id)
        if (changed) {
            DeploymentApplicationService.candidateContainerName(id)?.let { candidate ->
                runCatching {
                    val docker = DockerService(AppConfig.dockerSocket)
                    try { docker.deleteContainer(candidate) } finally { docker.close() }
                }
                    .onFailure { logger.warn("Unable to remove cancelled candidate {}", candidate, it) }
            }
        }
        return changed
    }

    private fun ensureNotCancelled(id: UUID) {
        if (DeploymentJobRepository.isCancelled(id)) throw CancellationException("Deployment cancelled")
    }

    private fun awaitHealthy(docker: DockerService, name: String, portMappings: Map<Int, Int>, job: DeploymentJobRecord): Boolean {
        val deadline = System.nanoTime() + job.readinessTimeoutSeconds * 1_000_000_000L
        val probe = selectedReadiness(job, portMappings.isNotEmpty())
        while (System.nanoTime() < deadline) {
            if (DeploymentJobRepository.isCancelled(job.id)) throw CancellationException("Deployment cancelled")
            if (runCatching { readinessProbe(docker, name, portMappings, job, probe) }.getOrDefault(false)) return true
            Thread.sleep(job.readinessIntervalSeconds * 1000L)
        }
        return false
    }

    private fun selectedReadiness(job: DeploymentJobRecord, hasPublishedPort: Boolean): String =
        job.readinessType?.lowercase() ?: if (hasPublishedPort) "tcp" else "process"

    private fun readinessProbe(docker: DockerService, name: String, portMappings: Map<Int, Int>, job: DeploymentJobRecord, probe: String): Boolean {
        if (docker.containerHealth(name) != "running") return false
        return when (probe) {
            "docker" -> docker.dockerHealthStatus(name) == "healthy"
            "http" -> {
                val target = requireNotNull(job.readinessTarget) { "HTTP readiness target is required" }
                val (containerPort, path) = target.split("/", limit = 2).let { parts ->
                    val port = parts.first().toIntOrNull() ?: job.containerPort
                        ?: error("HTTP readiness target must start with a container port")
                    port to "/" + parts.getOrNull(1).orEmpty()
                }
                val probeHostPort = portMappings[containerPort] ?: error("No published candidate mapping for HTTP container port $containerPort")
                val connection = (URL("http://127.0.0.1:$probeHostPort$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = job.readinessProbeTimeoutMillis
                    readTimeout = job.readinessProbeTimeoutMillis
                    requestMethod = "GET"
                }
                try { connection.responseCode in 200..299 } finally { connection.disconnect() }
            }
            "tcp" -> {
                val containerPort = job.readinessTarget?.toIntOrNull() ?: job.containerPort
                    ?: error("TCP readiness requires a container port")
                val hostPort = portMappings[containerPort] ?: error("No published candidate mapping for TCP container port $containerPort")
                tcpReachable(hostPort, job.readinessProbeTimeoutMillis)
            }
            "process" -> true
            else -> error("Unsupported readiness type: $probe")
        }
    }

    private fun tcpReachable(port: Int, timeoutMillis: Int = 500): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMillis); true }
    }.getOrDefault(false)

    private fun String.publishedHostPort(containerPort: Int): Int? = split(',').firstNotNullOfOrNull { mapping ->
        val parts = mapping.trim().substringBefore('/').split(':')
        parts.takeIf { it.size == 2 && it[1].toIntOrNull() == containerPort }
            ?.first()?.toIntOrNull()
    }

    private fun registryImage(registry: String, name: String, tag: String): String {
        val host = registry.trim().trimEnd('/')
        return if (host.isBlank() || host == "docker.io") "$name:$tag" else "$host/$name:$tag"
    }

    private fun runCommand(id: UUID, directory: Path, command: List<String>, githubToken: String = "") {
        val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        if (githubToken.isNotBlank()) {
            builder.environment()["GIT_CONFIG_COUNT"] = "1"
            builder.environment()["GIT_CONFIG_KEY_0"] = "http.extraheader"
            builder.environment()["GIT_CONFIG_VALUE_0"] = "AUTHORIZATION: bearer $githubToken"
        }
        val process = builder.start()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                DeploymentJobRepository.update(id, log = SecretValueCipher.redact(line, listOf(githubToken)))
            }
        }
        if (process.waitFor() != 0) error("Command failed: ${command.first()}")
    }

    private fun dockerLogin(id: UUID, registry: String, username: String, password: String) {
        val host = if (registry == "docker.io") "https://index.docker.io/v1/" else registry
        val process = ProcessBuilder("docker", "login", host, "--username", username, "--password-stdin")
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(password); it.newLine() }
        process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) error("Docker registry authentication failed for $registry")
        DeploymentJobRepository.update(id, "registry_authenticated", "Authenticated to registry $registry")
    }

    private fun commandOutput(directory: Path, command: List<String>): String = ProcessBuilder(command).directory(directory.toFile()).start().let { process ->
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) error("Unable to read checked-out commit")
        output
    }
}
