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
import com.gatekeeper.nginx.canDeploymentCutoverUpdateRoute
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
import java.util.UUID
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

object DeploymentWorker {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.deployment.DeploymentWorker")
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            var nextRecoveryAt = Long.MIN_VALUE
            while (isActive) {
                if (System.nanoTime() >= nextRecoveryAt) {
                    runCatching { recoverStaleJobs() }
                        .onFailure { logger.error("Stale deployment recovery failed", it) }
                    nextRecoveryAt = System.nanoTime() + TimeUnit.MINUTES.toNanos(1)
                }
                runCatching { processNext() }.onFailure { logger.error("Deployment worker cycle failed", it) }
                delay(1000.milliseconds)
            }
        }
    }

    private suspend fun processNext() {
        val job = DeploymentJobRepository.claimNext() ?: return
        val claimToken = requireNotNull(job.claimToken) { "Claim token missing for deployment ${job.id}" }
        DeploymentJobRecovery.executeClaimed(
            RecoverableDeploymentJob(job.id, job.serviceId, job.environment, claimToken),
            withLock = { key, block -> DistributedLock.withLock(key, block) },
            ownsClaim = { DeploymentJobRepository.ownsClaim(job.id, claimToken) },
            process = { runBlocking { processClaimedJob(job) } },
            release = { error ->
                DeploymentJobRepository.releaseClaim(
                    job.id,
                    claimToken,
                    "Deployment worker attempt interrupted: ${error.message ?: error::class.simpleName}"
                )
            }
        )
    }

    private fun recoverStaleJobs() {
        val cutoff = LocalDateTime.now().minusMinutes(AppConfig.deploymentStaleMinutes)
        val staleJobs = DeploymentJobRepository.staleRunningJobs(cutoff)
        DeploymentJobRecovery.recoverStale(
            staleJobs.map { RecoverableDeploymentJob(it.id, it.serviceId, it.environment, it.claimToken) },
            cutoff,
            LocalDateTime.now(),
            tryWithLock = { key, block -> DistributedLock.tryWithLock(key, block) },
            recover = { recoverable, staleBefore, recoveredAt ->
                val current = staleJobs.first { it.id == recoverable.id }
                DeploymentJobRepository.recoverStale(
                    current,
                    staleBefore,
                    recoveredAt,
                    "Recovered abandoned deployment after ${AppConfig.deploymentStaleMinutes} minutes without progress"
                )
            }
        )
    }

    private suspend fun processClaimedJob(job: DeploymentJobRecord) {
        if (job.currentStep == "readiness_succeeded" || job.currentStep == "cutover_in_progress") {
            val docker = DockerService(AppConfig.dockerSocket)
            var routeRollback: (() -> Boolean)? = null
            try {
                cutover(job, docker) { rollback -> routeRollback = rollback }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val routeRestored = routeRollback?.let { runCatching(it).getOrDefault(false) } ?: true
                if (!routeRestored) logger.error("Deployment {} failed after gateway cutover; candidate retained because gateway restoration failed", job.id)
                update(job, step = "readiness_succeeded", log = "Cutover attempt failed; ready candidate retained for retry: ${error.message}")
                AuditRepository.write(null, "deployment_cutover_failed", "deployment-worker", "job=${job.id} error=${error.message}")
            } finally {
                docker.close()
            }
            return
        }
        val workspace = withContext(Dispatchers.IO) {
            Files.createTempDirectory("gatekeeper-deployment-${job.id}-")
        }
        val deterministicCandidateName = deploymentContainerName(job.serviceName, job.id)
        var candidateName: String? = null
        var routeRollback: (() -> Boolean)? = null
        try {
            val recordedCandidateName = DeploymentApplicationService.candidateContainerName(job.id)
            val cleanupDocker = DockerService(AppConfig.dockerSocket)
            try {
                CandidateRetryRecovery.prepareForRetry(
                    deterministicName = deterministicCandidateName,
                    recordedName = recordedCandidateName,
                    containers = CandidateContainerBoundary { name -> cleanupDocker.removeContainerIfPresent(name) },
                    clearRecordedRuntime = { DeploymentApplicationService.clearCandidateRuntime(job.id) }
                )
            } finally {
                cleanupDocker.close()
            }
            ensureNotCancelled(job.id, requireNotNull(job.claimToken))
            val rollbackArtifact = DeploymentApplicationService.rollbackArtifact(job.id)
            val commit: String?
            val image: String
            if (rollbackArtifact != null) {
                commit = rollbackArtifact.commitSha
                image = rollbackArtifact.image
                update(job, "pulling", "Pulling rollback artifact $image", commitSha = commit)
            } else if (job.repository.isNullOrBlank()) {
                commit = null
                image = registryImage(job.registry, job.imageName, job.imageTag)
                update(job, "pulling", "Using prebuilt registry image $image")
            } else {
                val repository = job.repository
                update(job, "cloning", "Cloning $repository@${job.gitRef}")
                val githubToken = runCatching { GitHubAppClient.installationToken() }.getOrElse {
                    logger.info("GitHub App is not connected; attempting public repository clone for {}", repository)
                    ""
                }
                val cloneCommand = listOf("git", "clone", "--depth", "1", "--branch", job.gitRef, "https://github.com/$repository.git", workspace.toString())
                try {
                    runCommand(job.id, requireNotNull(job.claimToken), workspace, cloneCommand, githubToken)
                } catch (error: Exception) {
                    if (githubToken.isBlank()) throw error
                    logger.info("Authenticated clone unavailable for {}; retrying as public repository", repository)
                    workspace.toFile().deleteRecursively()
                    withContext(Dispatchers.IO) {
                        Files.createDirectories(workspace)
                    }
                    runCommand(job.id, requireNotNull(job.claimToken), workspace, cloneCommand)
                }
                commit = commandOutput(workspace, listOf("git", "rev-parse", "HEAD")).trim()
                update(job, "checked_out", "Repository checked out", commitSha = commit)
                image = registryImage(job.registry, job.imageName, job.imageTag)
            }
            DeploymentJobRepository.registryCredentialForDeployment(job.id)?.let { credential ->
                dockerLogin(job.id, requireNotNull(job.claimToken), job.registry, credential.username, credential.password)
            }
            if (rollbackArtifact == null && !job.repository.isNullOrBlank()) {
                update(job, "building", "Building $image")
                runCommand(job.id, requireNotNull(job.claimToken), workspace, listOf("docker", "build", "--tag", image, workspace.toString()))
                update(job, "pushing", "Pushing $image")
                runCommand(job.id, requireNotNull(job.claimToken), workspace, listOf("docker", "push", image))
            }
            update(job, "pulling", "Pulling $image on the deployment host")
            runCommand(job.id, requireNotNull(job.claimToken), workspace, listOf("docker", "pull", image))
            update(job, "starting_container", "Starting application container")
            ensureNotCancelled(job.id, requireNotNull(job.claimToken))
            val docker = DockerService(AppConfig.dockerSocket)
            try {
                val digest = docker.imageDigest(image)
                val immutableImageReference = docker.immutableImageReference(image)
                    ?: error("Docker could not determine an immutable image identity for deployment recovery")
                update(job, log = "Image digest: ${digest ?: "unavailable"}", imageDigest = digest)
                if (job.network != "bridge" && job.createNetworkIfMissing) docker.createNetworkIfMissing(job.network)
                candidateName = deterministicCandidateName
                val imagePorts = docker.imageExposedTcpPorts(image)
                val dynamicContainerPorts = imagePorts.ifEmpty { listOfNotNull(job.containerPort) }.toSet()
                update(
                    job,
                    log = if (imagePorts.isNotEmpty()) {
                        "Docker image declares TCP port(s): ${imagePorts.joinToString()}; these override saved wizard port values"
                    } else if (job.containerPort != null) {
                        "Docker image declares no TCP ports; using saved container port ${job.containerPort} as a fallback"
                    } else {
                        "Docker image declares no TCP ports; readiness will use container process state"
                    }
                )
                val created = docker.createContainer(CreateContainerRequest(
                    name = candidateName,
                    image = immutableImageReference,
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
                val selectedProbe = selectedReadiness(job, candidatePortMappings.isNotEmpty())
                update(job, step = "health_checking", log = "Waiting for candidate readiness using $selectedProbe probe")
                val readiness = awaitHealthy(docker, candidateName, candidatePortMappings, job)
                if (readiness == null) {
                    docker.deleteContainer(candidateName)
                    candidateName = null
                    error("Candidate container did not pass automatic readiness checks for port(s) ${dynamicContainerPorts.sorted().joinToString().ifBlank { "none declared" }}")
                }
                ensureNotCancelled(job.id, requireNotNull(job.claimToken))
                val resolvedContainerPort = readiness.containerPort ?: job.containerPort
                val candidateHostPort = resolvedContainerPort?.let(candidatePortMappings::get)
                check(DeploymentApplicationService.recordCandidateRuntime(
                    job.id, candidateName, candidateHostPort, resolvedContainerPort, candidatePortMappings,
                    immutableImageReference, listOf(job.network), candidatePortMappings.keys.associateWith { "127.0.0.1" }
                )) {
                    "Unable to persist candidate runtime for deployment ${job.id}"
                }
                update(
                    job,
                    step = "readiness_succeeded",
                    log = "Candidate passed readiness checks ($selectedProbe); resolved application container port ${resolvedContainerPort ?: "none"}. Container is ready; gateway cutover is a separate step"
                )
                AuditRepository.write(null, "deployment_readiness_succeeded", "deployment-worker", "job=${job.id} repository=${job.repository ?: "prebuilt-image"} commit=${commit ?: "not-applicable"}")
                cutover(job.copy(containerPort = resolvedContainerPort), docker) { rollback -> routeRollback = rollback }
                candidateName = null
            } finally { docker.close() }
        } catch (error: CancellationException) {
            candidateName?.let { name -> runCatching { val cleanupDocker = DockerService(AppConfig.dockerSocket); cleanupDocker.deleteContainer(name); cleanupDocker.close() } }
            if (DeploymentJobRepository.isCancelled(job.id)) {
                logger.info("Deployment {} cancelled", job.id)
            } else {
                throw error
            }
        } catch (error: Exception) {
            val candidateIsReady = DeploymentJobRepository.find(job.id)?.currentStep in setOf("readiness_succeeded", "cutover_in_progress")
            val routeRestored = routeRollback?.let { rollback -> runCatching(rollback).getOrDefault(false) } ?: true
            if (candidateIsReady && !routeRestored) {
                logger.error("Deployment {} route rollback failed; candidate remains the routed runtime and cutover requires operator review", job.id)
                throw error
            }
            if (candidateIsReady) {
                update(job, step = "readiness_succeeded", log = "Cutover attempt failed; ready candidate retained for retry: ${error.message}")
            }
            if (routeRestored && !candidateIsReady) candidateName?.let { name -> runCatching { val cleanupDocker = DockerService(AppConfig.dockerSocket); cleanupDocker.deleteContainer(name); cleanupDocker.close() } }
            else logger.error("Deployment {} failed after gateway cutover; candidate runtime retained because gateway restoration failed", job.id)
            if (!DeploymentJobRepository.isCancelled(job.id) && !candidateIsReady) {
                AuditRepository.write(null, "deployment_failed", "deployment-worker", "job=${job.id} error=${error.message}")
                update(job, "failed", error.message ?: "Deployment failed", status = "failed", error = error.message ?: "Deployment failed")
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun deploymentContainerName(serviceName: String, deploymentId: UUID): String {
        val normalizedServiceName = serviceName.lowercase()
            .replace(Regex("[^a-z0-9_.-]+"), "-")
            .trim('-', '.', '_')
            .ifBlank { "service" }
            .take(48)
            .trimEnd('-', '.', '_')
            .ifBlank { "service" }
        return "$normalizedServiceName-${deploymentId.toString().take(8)}"
    }

    private fun cutover(job: DeploymentJobRecord, docker: DockerService, onRouteRollback: ((() -> Boolean)?) -> Unit) {
        var durableState = DeploymentApplicationService.cutoverRecovery(job.id)
            ?: error("Cutover state is missing for deployment ${job.id}")
        if (durableState.state == "activated") {
            update(job, step = "active", log = "Deployment activation recovered", status = "succeeded")
            return
        }
        val runtime = DeploymentApplicationService.candidateRuntime(job.id)
            ?: error("Canonical candidate runtime missing for deployment ${job.id}")
        val candidateRuntimeName = requireNotNull(runtime.name) { "Candidate container name is missing" }
        check(docker.containerHealth(candidateRuntimeName) == "running") { "Ready candidate runtime is no longer running" }
        var previousDeployment = DeploymentApplicationService.activeRuntime(job.serviceId, job.environment)
        val previousContainer = previousDeployment?.name
        val changesProductionRoute = job.environment == "production"
        val site = if (changesProductionRoute) SiteRepository.findByServiceId(job.serviceId) else null
        val siteOwnerSlug = site?.projectSlug
        val siteService = if (site != null && siteOwnerSlug != null) NginxService.configured() else null
        val existingSiteConfig = if (siteService != null && siteOwnerSlug != null) siteService.inspectSite(siteOwnerSlug) else null
        val hasEnabledManagedSite = existingSiteConfig?.canDeploymentCutoverUpdateRoute() == true
        if (durableState.state == "not_started") {
            previousDeployment?.let { previous ->
                val observedRuntime = previous.name?.let(docker::adoptionDetails)
                check(observedRuntime != null || previous.triggerSource != "container_adoption") {
                    "Adopted previous runtime settings are incomplete without the original Docker container; refusing to retire it"
                }
                val immutableImage = observedRuntime?.imageDigest ?: observedRuntime?.imageId ?: previous.image
                    ?: error("Previous runtime has no immutable image identity; refusing to retire it")
                check(immutableImage.contains("@sha256:") || immutableImage.startsWith("sha256:")) {
                    "Previous runtime image identity is not immutable; refusing to retire it"
                }
                val attachedNetworks = observedRuntime?.networks?.takeIf { it.isNotEmpty() } ?: previous.networks
                val publishedPorts = observedRuntime?.ports?.takeIf { it.isNotEmpty() } ?: previous.ports
                val portHostIps = observedRuntime?.portHostIps?.takeIf { it.isNotEmpty() }
                    ?: previous.portHostIps.takeIf { it.isNotEmpty() }
                    ?: if (previous.triggerSource == "container_adoption") emptyMap()
                    else previous.ports.keys.associateWith { "127.0.0.1" }
                val runtimeOptions = observedRuntime?.runtimeOptions ?: previous.runtimeOptions
                check(publishedPorts.isEmpty() || portHostIps.keys.containsAll(publishedPorts.keys)) {
                    "Previous runtime published port addresses are unavailable; refusing to retire it"
                }
                check(DeploymentApplicationService.recordRuntimeRestorationSnapshot(
                    previous.id, immutableImage, publishedPorts, portHostIps, attachedNetworks, runtimeOptions
                )) {
                    "Unable to persist previous runtime restoration snapshot"
                }
                previousDeployment = previous.copy(
                    image = immutableImage, ports = publishedPorts, networks = attachedNetworks,
                    portHostIps = portHostIps, runtimeOptions = runtimeOptions
                )
            }
            check(DeploymentApplicationService.prepareRuntimeCutover(job.id, previousDeployment?.id, existingSiteConfig?.content)) {
                "Unable to persist cutover recovery information"
            }
            durableState = DeploymentApplicationService.cutoverRecovery(job.id) ?: error("Cutover recovery information disappeared")
        }
        var rollbackRoute: (() -> Boolean)? = null
        if (hasEnabledManagedSite) {
            val managedSite = requireNotNull(site)
            val ownerSlug = requireNotNull(siteOwnerSlug)
            val activeSiteService = requireNotNull(siteService)
            val recovery = DeploymentApplicationService.cutoverRecovery(job.id) ?: error("Cutover state disappeared")
            val previousConfig = recovery.previousNginxConfig ?: existingSiteConfig.content
            val containerPort = managedSite.upstreamExplicitPort ?: job.containerPort
                ?: error("The image declares no TCP EXPOSE port, so Gatekeeperd could not infer an upstream for the managed site. Add the application's listening port to the image metadata with EXPOSE.")
            val hostPort = runtime.ports[containerPort]
                ?: if (site.upstreamMode == com.gatekeeper.db.tables.UpstreamMode.EXPLICIT_PORT) runtime.hostPort
                    ?: error("Candidate runtime host port is missing")
                else error("Candidate does not publish managed site container port $containerPort")
            rollbackRoute = {
                val restored = previousConfig?.let { activeSiteService.restoreSiteConfiguration(ownerSlug, it) } ?: false
                if (restored) SiteRepository.restoreDeploymentUpstreamForSite(managedSite) != null else false
            }
            onRouteRollback(rollbackRoute)

            if (recovery.state in setOf("restoring", "runtime_restored", "restore_failed")) {
                onRouteRollback(null)
                restorePreviousRuntime(job.id, previousDeployment, previousConfig, docker, activeSiteService, ownerSlug, managedSite)
            }
            check(activeSiteService.switchDeploymentUpstream(job.serviceId, ownerSlug, "127.0.0.1", containerPort, hostPort)) {
                "Gateway/site upstream cutover failed; previous runtime remains active"
            }
            check(DeploymentApplicationService.updateCutoverState(job.id, "route_switched")) { "Unable to persist route cutover state" }
        } else if (durableState.state in setOf("restoring", "runtime_restored", "restore_failed")) {
            onRouteRollback(null)
            restorePreviousRuntime(job.id, previousDeployment, durableState.previousNginxConfig, docker, null, null, null)
        }

        if (changesProductionRoute && previousDeployment != null && previousContainer != null && previousContainer != candidateRuntimeName) {
            check(DeploymentApplicationService.updateCutoverState(job.id, "retiring_previous")) { "Unable to persist previous-runtime retirement state" }
            try {
                docker.getContainer(previousContainer)?.let {
                    if (docker.containerHealth(previousContainer) == "running") docker.stopContainer(previousContainer)
                    docker.deleteContainer(previousContainer)
                }
                check(DeploymentApplicationService.updateCutoverState(job.id, "previous_retired")) {
                    "Unable to persist previous-runtime retirement completion"
                }
            } catch (retirementError: Exception) {
                onRouteRollback(null)
                restorePreviousRuntime(
                    job.id, previousDeployment, durableState.previousNginxConfig,
                    docker, siteService, siteOwnerSlug, site
                )
                throw retirementError
            }
        }
        PreviousRuntimeRecovery.activateOrRestore(
            activate = {
                check(DeploymentApplicationService.activateAfterCutover(job.id, previousDeployment?.id)) {
                    "Canonical deployment activation failed after cutover"
                }
            },
            restore = {
                onRouteRollback(null)
                restorePreviousRuntime(
                job.id, previousDeployment, durableState.previousNginxConfig ?: existingSiteConfig?.content,
                docker, siteService, siteOwnerSlug, site
                )
            }
        )
        update(
            job,
            step = "active",
            log = if (hasEnabledManagedSite) "Gateway switched to candidate; previous runtime retired" else "Deployment activated; Nginx site creation/activation remains a separate action",
            status = "succeeded"
        )
        DeploymentApplicationService.rollbackTargetId(job.id)?.let { targetId ->
            AuditRepository.write(null, "deployment_rollback_activated", "deployment-worker", "deployment=${job.id} rolled_back_to=$targetId")
        }
        if (previousContainer != null && previousContainer != candidateRuntimeName) {
            previousDeployment?.let { previous ->
                AuditRepository.write(null, "deployment_runtime_superseded", "deployment-worker", "deployment=${previous.id} container=$previousContainer")
            }
        }
    }

    private fun restorePreviousRuntime(
        deploymentId: UUID,
        previous: DeploymentApplicationService.ActiveRuntime?,
        previousNginxConfig: String?,
        docker: DockerService,
        nginx: NginxService?,
        ownerSlug: String?,
        site: SiteRepository.SiteRecord?
    ) {
        val secretValues = previous?.let { DeploymentJobRepository.resolveEnvironmentForExecution(it.executionId).values }.orEmpty()
        val image = previous?.let { runtime ->
            runtime.image ?: runtime.name?.let(docker::containerImageId)?.also {
                check(DeploymentApplicationService.recordRuntimeImageReference(runtime.id, it)) { "Unable to persist immutable previous image identity" }
            }
        }
        val runtimeNetworks = previous?.let { runtime ->
            val actual = runtime.name?.let(docker::getContainer)?.networks.orEmpty()
            (actual.ifEmpty { runtime.networks }).also {
                check(DeploymentApplicationService.recordRuntimeNetworkAttachments(runtime.id, it)) { "Unable to persist previous runtime networks" }
            }
        }.orEmpty()
        val plan = previous?.let { runtime ->
            val immutableImage = image ?: error("Previous runtime has no immutable image identity; refusing tag-based restoration")
            check(immutableImage.contains("@sha256:") || immutableImage.startsWith("sha256:")) {
                "Previous runtime image identity is not immutable"
            }
            check(runtime.name != null) { "Previous runtime container name is unavailable" }
            check(runtime.ports.isNotEmpty() || (runtime.hostPort != null && runtime.containerPort != null)) {
                "Previous runtime published port mappings are unavailable"
            }
            val portMap = runtime.ports.ifEmpty { mapOf(runtime.containerPort!! to runtime.hostPort!!) }
            RuntimeRestorePlan(
                containerName = runtime.name,
                imageReference = immutableImage,
                publishedPorts = portMap,
                portHostIps = runtime.portHostIps,
                networkMode = runtimeNetworks.firstOrNull() ?: runtime.network,
                networkAttachments = runtimeNetworks,
                restartPolicy = runtime.restartPolicy,
                environment = secretValues,
                volumes = runtime.volumes,
                runtimeOptions = runtime.runtimeOptions
            )
        }
        PreviousRuntimeRecovery.restore(
            plan = plan,
            previousNginxConfig = previousNginxConfig,
            docker = RuntimeRestoreDockerBoundary { restorePlan ->
                val hostToContainer = restorePlan.publishedPorts.entries.associate { (containerPort, hostPort) -> hostPort to containerPort }
                docker.ensureRestoredContainer(CreateContainerRequest(
                    name = restorePlan.containerName,
                    image = restorePlan.imageReference,
                    ports = hostToContainer,
                    env = restorePlan.environment,
                    network = restorePlan.networkMode,
                    additionalNetworks = restorePlan.networkAttachments,
                    volumes = restorePlan.volumes,
                    restartPolicy = restorePlan.restartPolicy,
                    pullImage = false,
                    portHostIp = restorePlan.portHostIp,
                    portHostIps = restorePlan.portHostIps,
                    runtimeOptions = restorePlan.runtimeOptions
                ))
            },
            route = RuntimeRestoreRouteBoundary { config ->
                val routeRestored = if (nginx != null && ownerSlug != null) nginx.restoreSiteConfiguration(ownerSlug, config) else true
                if (routeRestored && site != null) SiteRepository.restoreDeploymentUpstreamForSite(site) != null else routeRestored
            },
            state = RuntimeRestoreStateBoundary { state, error ->
                check(DeploymentApplicationService.updateCutoverState(deploymentId, state, error)) { "Unable to persist cutover restoration state" }
            },
            secrets = secretValues.values
        )
    }

    fun rollback(id: java.util.UUID): UUID? {
        return runCatching { DeploymentJobRepository.createRollback(id) }
            .onFailure { logger.warn("Unable to queue rollback deployment for {}", id, it) }
            .getOrNull()
    }

    private fun update(
        job: DeploymentJobRecord,
        step: String? = null,
        log: String? = null,
        commitSha: String? = null,
        imageDigest: String? = null,
        status: String? = null,
        error: String? = null
    ) {
        DeploymentJobRepository.update(
            job.id,
            requireNotNull(job.claimToken) { "Claim token missing for deployment ${job.id}" },
            step,
            log,
            commitSha,
            imageDigest,
            status,
            error
        )
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

    private fun ensureNotCancelled(id: UUID, claimToken: UUID) {
        if (!DeploymentJobRepository.ownsClaim(id, claimToken)) {
            throw CancellationException("Deployment was cancelled or this worker no longer owns its claim")
        }
    }

    private data class ReadinessResult(val containerPort: Int?)

    private fun awaitHealthy(docker: DockerService, name: String, portMappings: Map<Int, Int>, job: DeploymentJobRecord): ReadinessResult? {
        val deadline = System.nanoTime() + job.readinessTimeoutSeconds * 1_000_000_000L
        val probe = selectedReadiness(job, portMappings.isNotEmpty())
        while (System.nanoTime() < deadline) {
            ensureNotCancelled(job.id, requireNotNull(job.claimToken))
            runCatching { readinessProbe(docker, name, portMappings, job, probe) }.getOrNull()?.let { return it }
            Thread.sleep(job.readinessIntervalSeconds * 1000L)
        }
        return null
    }

    private fun selectedReadiness(job: DeploymentJobRecord, hasPublishedPort: Boolean): String =
        when (job.readinessType?.lowercase()) {
            "docker" -> "docker"
            "process" -> "process"
            else -> if (hasPublishedPort) "tcp (auto-detected)" else "process"
        }

    private fun readinessProbe(docker: DockerService, name: String, portMappings: Map<Int, Int>, job: DeploymentJobRecord, probe: String): ReadinessResult? {
        if (docker.containerHealth(name) != "running") return null
        val openDeclaredPort = {
            portMappings.entries.firstOrNull { (_, hostPort) -> tcpReachable(hostPort, job.readinessProbeTimeoutMillis) }?.key
        }
        return when (probe) {
            "docker" -> if (docker.dockerHealthStatus(name) == "healthy") ReadinessResult(openDeclaredPort()) else null
            "tcp (auto-detected)" -> openDeclaredPort()?.let(::ReadinessResult)
            "process" -> ReadinessResult(openDeclaredPort() ?: portMappings.keys.firstOrNull())
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

    private fun runCommand(id: UUID, claimToken: UUID, directory: Path, command: List<String>, githubToken: String = "") {
        ensureNotCancelled(id, claimToken)
        val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        if (githubToken.isNotBlank()) {
            builder.environment()["GIT_CONFIG_COUNT"] = "1"
            builder.environment()["GIT_CONFIG_KEY_0"] = "http.extraheader"
            builder.environment()["GIT_CONFIG_VALUE_0"] = "AUTHORIZATION: bearer $githubToken"
        }
        val process = builder.start()
        val outputThread = Thread({
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    DeploymentJobRepository.update(id, claimToken, log = SecretValueCipher.redact(line, listOf(githubToken)))
                }
            }
        }, "deployment-output-${id}").apply { isDaemon = true; start() }
        try {
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) ensureNotCancelled(id, claimToken)
            outputThread.join()
            ensureNotCancelled(id, claimToken)
            if (process.exitValue() != 0) error("Command failed: ${command.first()}")
        } catch (cancelled: CancellationException) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            outputThread.join(1_000)
            throw cancelled
        }
    }

    private fun dockerLogin(id: UUID, claimToken: UUID, registry: String, username: String, password: String) {
        ensureNotCancelled(id, claimToken)
        val host = if (registry == "docker.io") "https://index.docker.io/v1/" else registry
        val process = ProcessBuilder("docker", "login", host, "--username", username, "--password-stdin")
            .redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(password); it.newLine() }
        process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) error("Docker registry authentication failed for $registry")
        ensureNotCancelled(id, claimToken)
        DeploymentJobRepository.update(id, claimToken, "registry_authenticated", "Authenticated to registry $registry")
    }

    private fun commandOutput(directory: Path, command: List<String>): String = ProcessBuilder(command).directory(directory.toFile()).start().let { process ->
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) error("Unable to read checked-out commit")
        output
    }
}
