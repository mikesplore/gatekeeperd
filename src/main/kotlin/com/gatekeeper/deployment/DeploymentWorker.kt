package com.gatekeeper.deployment

import com.gatekeeper.db.repositories.DeploymentJobRepository
import com.gatekeeper.db.repositories.DeploymentJobRecord
import com.gatekeeper.core.ports.DeploymentLock
import com.gatekeeper.feature.deployment.domain.repository.DeploymentPortAllocationRepository
import com.gatekeeper.feature.audit.domain.repository.AuditRepository
import com.gatekeeper.db.repositories.RegistryCredentialRepository
import com.gatekeeper.config.AppConfig
import com.gatekeeper.plugins.DistributedLock
import com.gatekeeper.docker.CreateContainerRequest
import com.gatekeeper.docker.DockerService
import com.gatekeeper.infrastructure.TcpPortProbe
import com.gatekeeper.infrastructure.TcpHttpPortProbe
import com.gatekeeper.nginx.NginxService
import com.gatekeeper.nginx.DeploymentUpstreamIncludes
import com.gatekeeper.deployment.DurableCutoverFiles
import com.gatekeeper.nginx.canDeploymentCutoverUpdateRoute
import com.gatekeeper.db.repositories.SiteRepository
import com.gatekeeper.db.repositories.ServiceDomainRepository
import com.gatekeeper.security.SecretValueCipher
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class DeploymentWorker(
    private val portAllocationRepository: DeploymentPortAllocationRepository,
    private val sourceFetcher: SourceFetcher,
    private val imageBuilder: ImageBuilder,
    private val auditRepository: AuditRepository,
    private val deploymentQueue: DeploymentQueue,
    private val deploymentLock: DeploymentLock
) {
    private val logger = LoggerFactory.getLogger("com.gatekeeper.deployment.DeploymentWorker")
    private val started = AtomicBoolean(false)
    private val readinessChecker = ReadinessChecker(TcpPortProbe(), TcpHttpPortProbe())

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            runCatching { recoverInterruptedCutovers() }
                .onFailure { logger.error("Interrupted deployment cutover recovery failed", it) }
            var nextRecoveryAt = Long.MIN_VALUE
            while (isActive) {
                if (System.nanoTime() >= nextRecoveryAt) {
                    runCatching { recoverInterruptedCutovers() }
                        .onFailure { logger.error("Interrupted deployment cutover recovery failed", it) }
                    runCatching { recoverStaleJobs() }
                        .onFailure { logger.error("Stale deployment recovery failed", it) }
                    nextRecoveryAt = System.nanoTime() + TimeUnit.MINUTES.toNanos(1)
                }
                runCatching { processNext() }.onFailure { logger.error("Deployment worker cycle failed", it) }
                delay(1000.milliseconds)
            }
        }
    }

    private fun cleanupInterruptedCandidate(deploymentId: UUID, candidateName: String?, reason: String): Boolean {
        if (candidateName.isNullOrBlank()) return true
        return runCatching {
            val cleanupDocker = DockerService(AppConfig.dockerSocket)
            try {
                cleanupDocker.removeContainerIfPresent(candidateName)
            } finally {
                cleanupDocker.close()
            }
        }.onSuccess { removed ->
            if (removed) {
                DeploymentApplicationService.clearCandidateRuntimeAfterCutoverRecovery(deploymentId)
                auditRepository.write(
                    null,
                    "deployment_candidate_cleaned_up",
                    "deployment-worker",
                    "deployment=$deploymentId container=$candidateName reason=$reason"
                )
            }
        }.getOrElse { error ->
            logger.error("Could not remove interrupted deployment candidate {} during recovery", candidateName, error)
            false
        }
    }

    private fun recoverInterruptedCutovers() {
        for ((deploymentId, recovery) in DeploymentApplicationService.pendingCutovers()) {
            val serviceId = recovery.serviceId ?: continue
            val lockKey = "deployment-service:$serviceId:${recovery.environment}"
            DistributedLock.tryWithLock(lockKey) {
                val site = SiteRepository.findByServiceId(serviceId)
                val files = recovery.files
                val candidate = DeploymentApplicationService.candidateRuntime(deploymentId)
                if (site == null || files == null) {
                    cleanupInterruptedCandidate(deploymentId, candidate?.name, "missing recovery data or site")
                    DeploymentApplicationService.updateCutoverState(deploymentId, "repair_required", "Pending upstream recovery data or site is missing")
                    return@tryWithLock
                }
                val docker = runCatching { DockerService(AppConfig.dockerSocket) }.getOrNull()
                val candidateRunning = try {
                    candidate?.name?.let { docker?.containerHealth(it) == "running" } == true
                } finally {
                    runCatching { docker?.close() }
                }
                val restoringFailedCutover = recovery.state == "repair_required"
                val result = NginxService.configured().recoverInterruptedUpstream(
                    site, files, candidateRunning && !restoringFailedCutover
                )
                when (result) {
                    NginxService.InterruptedCutoverResult.TARGET_RELOADED,
                    NginxService.InterruptedCutoverResult.PREVIOUS_RESTORED -> {
                        if (restoringFailedCutover && result == NginxService.InterruptedCutoverResult.PREVIOUS_RESTORED) {
                            val removed = cleanupInterruptedCandidate(
                                deploymentId,
                                candidate?.name,
                                "repair-required cutover recovery"
                            )
                            if (removed) {
                                DeploymentApplicationService.updateCutoverState(
                                    deploymentId, "restored", "Previous gateway route restored and failed candidate removed"
                                )
                            }
                        } else if (result == NginxService.InterruptedCutoverResult.PREVIOUS_RESTORED) {
                            val removed = cleanupInterruptedCandidate(
                                deploymentId,
                                candidate?.name,
                                "interrupted cutover restored previous route"
                            )
                            if (removed) {
                                DeploymentJobRepository.failInterruptedCutover(
                                    deploymentId,
                                    "Interrupted cutover restored the previous gateway route; discarded ready candidate to avoid stale port conflicts"
                                )
                                DeploymentApplicationService.updateCutoverState(
                                    deploymentId,
                                    "restored",
                                    "Previous gateway route restored and interrupted candidate removed"
                                )
                            }
                        } else if (candidateRunning) {
                            DeploymentJobRepository.requeueInterruptedCutover(deploymentId)
                        } else {
                            DeploymentJobRepository.failInterruptedCutover(
                                deploymentId, "Candidate runtime was not running during startup cutover recovery"
                            )
                        }
                    }
                    NginxService.InterruptedCutoverResult.CONFLICT -> {
                        DeploymentApplicationService.updateCutoverState(deploymentId, "repair_required", "Upstream include checksum matches neither saved cutover target nor previous content")
                    }
                }
            }
        }
    }

    private suspend fun processNext() {
        val job = deploymentQueue.claimNext() ?: return
        val claimToken = requireNotNull(job.claimToken) { "Claim token missing for deployment ${job.id}" }
        DeploymentJobRecovery.executeClaimed(
            RecoverableDeploymentJob(job.id, job.serviceId, job.environment, claimToken),
            withLock = { key, block -> deploymentLock.withLock(key, block) },
            ownsClaim = { deploymentQueue.ownsClaim(job.id, claimToken) },
            process = { runBlocking { processClaimedJob(job) } },
            release = { error ->
                deploymentQueue.releaseClaim(
                    job.id,
                    claimToken,
                    "Deployment worker attempt interrupted: ${error.message ?: error::class.simpleName}"
                )
            }
        )
    }

    private fun recoverStaleJobs() {
        val cutoff = LocalDateTime.now().minusMinutes(AppConfig.deploymentStaleMinutes)
        val staleJobs = deploymentQueue.staleRunningJobs(cutoff)
        DeploymentJobRecovery.recoverStale(
            staleJobs.map { RecoverableDeploymentJob(it.id, it.serviceId, it.environment, it.claimToken) },
            cutoff,
            LocalDateTime.now(),
            tryWithLock = { key, block -> deploymentLock.tryWithLock(key, block) },
            recover = { recoverable, staleBefore, recoveredAt ->
                val current = staleJobs.first { it.id == recoverable.id }
                deploymentQueue.recoverStale(
                    current,
                    staleBefore,
                    recoveredAt,
                    "Recovered abandoned deployment after ${AppConfig.deploymentStaleMinutes} minutes without progress"
                )
            }
        )
    }

    private suspend fun processClaimedJob(claimedJob: DeploymentJobRecord) {
        var job = claimedJob
        val site = if (job.environment == "production") SiteRepository.findByServiceId(job.serviceId) else null
        if (job.containerPort == null) {
            job = job.copy(containerPort = DeploymentSiteCutoverPolicy.primaryContainerPort(
                null, site?.upstreamKind, site?.upstreamExplicitPort
            ))
        }
        DeploymentApplicationService.deploymentBlockReason(job.serviceId, job.environment)?.let { reason ->
            update(job, step = "failed", log = reason, status = "failed", error = reason)
            return
        }
        if (job.currentStep == "readiness_succeeded" || job.currentStep == "cutover_in_progress") {
            val docker = DockerService(AppConfig.dockerSocket)
            var routeRollback: (() -> Boolean)? = null
            try {
                cutover(job, docker, auditRepository) { rollback -> routeRollback = rollback }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val routeRestored = routeRollback?.let { runCatching(it).getOrDefault(false) } ?: true
                if (!routeRestored) {
                    DeploymentApplicationService.updateCutoverState(job.id, "repair_required", "Gateway restoration failed after cutover error")
                    logger.error("Deployment {} failed after gateway cutover; candidate retained because gateway restoration failed", job.id)
                    update(
                        job,
                        step = "failed",
                        log = "Cutover failed and the previous gateway route could not be restored; operator repair is required: ${error.message}",
                        status = "failed",
                        error = "Cutover failed and gateway route restoration requires operator repair: ${error.message}"
                    )
                } else {
                    val requeued = deploymentQueue.requeueReadyCutover(
                        job.id,
                        requireNotNull(job.claimToken),
                        error.message ?: "Cutover failed"
                    )
                    if (!requeued) logger.warn("Deployment {} cutover retry was not queued because its claim changed", job.id)
                }
                auditRepository.write(null, "deployment_cutover_failed", "deployment-worker", "job=${job.id} error=${error.message}")
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
        var candidateContainerId: String? = null
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
            var imageBuiltLocally = false
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
                commit = sourceFetcher.fetch(
                    repository = repository,
                    gitRef = job.gitRef,
                    workspace = workspace,
                    resetWorkspace = {
                    workspace.toFile().deleteRecursively()
                        Files.createDirectories(workspace)
                    },
                    checkCancelled = { ensureNotCancelled(job.id, requireNotNull(job.claimToken)) },
                    onLog = logger::info,
                    onOutput = { line, token ->
                        update(job, log = SecretValueCipher.redact(line, listOf(token)))
                    }
                )
                update(job, "checked_out", "Repository checked out", commitSha = commit)
                image = registryImage(job.registry, job.imageName, job.imageTag)
            }
            deploymentQueue.registryCredentialForDeployment(job.id)?.let { credential ->
                imageBuilder.authenticate(job.registry, credential.username, credential.password) {
                    ensureNotCancelled(job.id, requireNotNull(job.claimToken))
                }
                deploymentQueue.update(job.id, requireNotNull(job.claimToken), "registry_authenticated", "Authenticated to registry ${job.registry}")
            }
            if (rollbackArtifact == null && !job.repository.isNullOrBlank()) {
                update(job, "building", "Building $image")
                imageBuilder.build(image, workspace, { ensureNotCancelled(job.id, requireNotNull(job.claimToken)) }) { line ->
                    deploymentQueue.update(job.id, requireNotNull(job.claimToken), log = line)
                }
                imageBuiltLocally = true
                update(job, "pushing", "Pushing $image")
                imageBuilder.push(image, workspace, { ensureNotCancelled(job.id, requireNotNull(job.claimToken)) }) { line ->
                    deploymentQueue.update(job.id, requireNotNull(job.claimToken), log = line)
                }
            }
            if (!imageBuiltLocally) {
                update(job, "pulling", "Pulling $image on the deployment host")
                imageBuilder.pull(image, workspace, { ensureNotCancelled(job.id, requireNotNull(job.claimToken)) }) { line ->
                    deploymentQueue.update(job.id, requireNotNull(job.claimToken), log = line)
                }
            } else {
                update(job, "image_ready", "Using image built during this deployment")
            }
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
                val managedSite = if (job.environment == "production") SiteRepository.findByServiceId(job.serviceId) else null
                if (DeploymentSiteCutoverPolicy.hasDeploymentRoute(managedSite?.upstreamKind) && job.containerPort == null && imagePorts.size != 1) {
                    error("Managed service must configure a primary container port when its image exposes zero or multiple TCP ports")
                }
                val dynamicContainerPorts = (imagePorts + listOfNotNull(job.containerPort)).toSet()
                update(
                    job,
                    log = if (imagePorts.isNotEmpty()) {
                        "Docker image declares TCP port(s): ${imagePorts.joinToString()}; configured primary port ${job.containerPort ?: "not set"} is preserved"
                    } else if (job.containerPort != null) {
                        "Docker image declares no TCP ports; using saved container port ${job.containerPort} as a fallback"
                    } else {
                        "Docker image declares no TCP ports; readiness will use container process state"
                    }
                )
                val activePorts = DeploymentApplicationService.activeRuntime(job.serviceId, job.environment)?.ports.orEmpty()
                val rejectedPorts = mutableSetOf<Int>()
                var reservedPorts = emptyMap<Int, Int>()
                var created: Pair<String, com.gatekeeper.docker.ContainerInfo>? = null
                for (attempt in 0 until 4) {
                    reservedPorts = if (dynamicContainerPorts.isEmpty()) emptyMap() else {
                        val site = if (job.environment == "production") SiteRepository.findByServiceId(job.serviceId) else null
                        portAllocationRepository.allocateSlot(
                            serviceId = job.serviceId,
                            environment = job.environment,
                            activePorts = activePorts,
                            containerPorts = dynamicContainerPorts,
                            dockerPortsInUse = docker.hostPortsInUse() + HostPortListeners.read(),
                            portRange = AppConfig.deploymentHostPortRange,
                            rejectedPorts = rejectedPorts,
                            preferredHostPorts = buildMap {
                                val explicitPort = site?.upstreamExplicitPort
                                val containerPort = job.containerPort
                                if (explicitPort != null && containerPort != null) put(containerPort, explicitPort)
                            }
                        )
                    }
                    if (reservedPorts.isNotEmpty()) {
                        update(
                            job,
                            log = "Reserved stable host ports for candidate: " +
                                reservedPorts.entries.sortedBy { it.key }.joinToString { "${it.value}->${it.key}" }
                        )
                    }
                    try {
                        val result = docker.createContainerTracked(CreateContainerRequest(
                            name = candidateName,
                            image = immutableImageReference,
                            ports = reservedPorts.map { (containerPort, hostPort) -> hostPort to containerPort }.toMap(),
                            portHostIp = "127.0.0.1",
                            network = job.network,
                            restartPolicy = job.restartPolicy,
                            env = DeploymentJobRepository.resolveEnvironmentForExecution(job.id).values,
                            volumes = job.volumes,
                            pullImage = false
                        ))
                        created = result.id to result.info
                        break
                    } catch (error: Exception) {
                        if (!isHostPortConflict(error) || attempt == 3 || reservedPorts.isEmpty()) throw error
                        rejectedPorts += reservedPorts.values
                        update(job, log = "Docker could not bind reserved port(s) ${reservedPorts.values.joinToString()}; reallocating from the configured pool")
                    }
                }
                val (createdId, candidateInfo) = created
                    ?: error("Docker did not create the candidate container")
                candidateContainerId = createdId
                val candidatePortMappings = dynamicContainerPorts.associateWith { containerPort ->
                    candidateInfo.ports.publishedHostPort(containerPort)
                        ?: error("Docker did not assign a host port for candidate container port $containerPort")
                }
                val selectedProbe = readinessChecker.selectedProbe(job, candidatePortMappings.isNotEmpty())
                update(job, step = "health_checking", log = "Waiting for candidate readiness using $selectedProbe probe")
                val readiness = awaitHealthy(docker, candidateName, candidatePortMappings, job)
                if (readiness == null) {
                    val failure = IllegalStateException(
                        "Candidate container did not pass automatic readiness checks for port(s) ${dynamicContainerPorts.sorted().joinToString().ifBlank { "none declared" }}"
                    )
                    candidateContainerId?.let { removeCandidatePreservingFailure(docker, it, failure) }
                    candidateContainerId = null
                    candidateName = null
                    throw failure
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
                auditRepository.write(null, "deployment_readiness_succeeded", "deployment-worker", "job=${job.id} repository=${job.repository ?: "prebuilt-image"} commit=${commit ?: "not-applicable"}")
                var cutoverSucceeded = false
                cutover(job.copy(containerPort = resolvedContainerPort), docker, auditRepository) { rollback -> routeRollback = rollback }
                cutoverSucceeded = true
                if (cutoverSucceeded) {
                    candidateName = null
                    candidateContainerId = null
                }
            } finally { docker.close() }
        } catch (error: CancellationException) {
            candidateContainerId?.let { id -> removeCandidatePreservingFailure(null, id, error) }
            if (DeploymentJobRepository.isCancelled(job.id)) {
                logger.info("Deployment {} cancelled", job.id)
            } else {
                throw error
            }
        } catch (error: Exception) {
            val candidateIsReady = DeploymentJobRepository.find(job.id)?.currentStep in setOf("readiness_succeeded", "cutover_in_progress")
            val routeRestored = routeRollback?.let { rollback -> runCatching(rollback).getOrDefault(false) } ?: true
            if (candidateIsReady && !routeRestored) {
                DeploymentApplicationService.updateCutoverState(job.id, "repair_required", "Gateway restoration failed after cutover error")
                logger.error("Deployment {} route rollback failed; candidate remains the routed runtime and cutover requires operator review", job.id)
                update(
                    job,
                    step = "failed",
                    log = "Cutover failed and the previous gateway route could not be restored; operator repair is required: ${error.message}",
                    status = "failed",
                    error = "Cutover failed and gateway route restoration requires operator repair: ${error.message}"
                )
                auditRepository.write(null, "deployment_cutover_failed", "deployment-worker", "job=${job.id} error=${error.message}")
                return
            }
            if (candidateIsReady) {
                val requeued = DeploymentJobRepository.requeueReadyCutover(
                    job.id,
                    requireNotNull(job.claimToken),
                    error.message ?: "Cutover failed"
                )
                if (!requeued) logger.warn("Deployment {} cutover retry was not queued because its claim changed", job.id)
            }
            if (routeRestored && !candidateIsReady) candidateContainerId?.let { id -> removeCandidatePreservingFailure(null, id, error) }
            else if (!routeRestored) logger.error(
                "Deployment {} failed after gateway cutover; candidate runtime retained because gateway restoration failed",
                job.id
            ) else logger.info(
                "Deployment {} cutover failed; previous route was restored and ready candidate retained for retry",
                job.id
            )
            if (!DeploymentJobRepository.isCancelled(job.id) && !candidateIsReady) {
                auditRepository.write(null, "deployment_failed", "deployment-worker", "job=${job.id} error=${error.message}")
                update(job, "failed", error.message ?: "Deployment failed", status = "failed", error = error.message ?: "Deployment failed")
            }
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun removeCandidatePreservingFailure(
        existingDocker: DockerService?,
        containerId: String,
        failure: Throwable
    ) {
        var cleanupDocker = existingDocker
        try {
            if (cleanupDocker == null) cleanupDocker = DockerService(AppConfig.dockerSocket)
            cleanupDocker.removeContainerByIdIfPresent(containerId)
        } catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            logger.warn("Could not remove failed deployment candidate {}", containerId, cleanupFailure)
        } finally {
            if (existingDocker == null) runCatching { cleanupDocker?.close() }
        }
    }

    private fun isHostPortConflict(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .mapNotNull { it.message?.lowercase() }
        .any { message ->
            "address already in use" in message ||
                "port is already allocated" in message ||
                "failed to bind" in message ||
                "bind for 127.0.0.1" in message
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

    private fun cutover(job: DeploymentJobRecord, docker: DockerService, auditRepository: AuditRepository, onRouteRollback: ((() -> Boolean)?) -> Unit) {
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
        // Legacy explicit sites may already receive managed deployments. Convert them on cutover
        // so deployment changes only the stable service include after this first activation.
        val convertsLegacySite = DeploymentSiteCutoverPolicy.convertsLegacySite(site?.upstreamKind)
        val hasEnabledManagedSite = DeploymentSiteCutoverPolicy.hasDeploymentRoute(site?.upstreamKind) &&
            existingSiteConfig?.canDeploymentCutoverUpdateRoute() == true
        val managedRoute = if (hasEnabledManagedSite) {
            val managedSite = requireNotNull(site)
            val containerPort = job.containerPort ?: runtime.ports.keys.singleOrNull()
                ?: error("Managed service needs an explicit primary container port when the image exposes multiple TCP ports")
            val hostPort = runtime.ports[containerPort]
                ?: error("Candidate does not publish managed service primary container port $containerPort")
            val path = job.readinessTarget?.takeIf { it.startsWith("/") } ?: "/"
            check(com.gatekeeper.nginx.probeCandidateLoopback(hostPort, managedSite.domain, path)) {
                "Candidate failed its direct loopback HTTP probe before nginx cutover"
            }
            containerPort to hostPort
        } else null
        val managedPreviousHostPort = managedRoute?.let { route ->
            previousDeployment?.ports?.get(route.first) ?: previousDeployment?.hostPort
                ?: if (convertsLegacySite) site?.upstreamExplicitPort else null
        }
        val durableCutoverFiles = managedRoute?.let { route ->
            val serviceId = job.serviceId
            val includes = DeploymentUpstreamIncludes(File(AppConfig.nginxUpstreamIncludeDirectory))
            val oldInclude = includes.file(serviceId).takeIf { it.isFile }?.readText()
            val candidateInclude = includes.render(serviceId, route.second)
            DurableCutoverFiles(
                previousSiteConfig = existingSiteConfig?.content,
                siteSlug = siteOwnerSlug,
                previousUpstreamKind = site?.upstreamKind,
                previousIncludeContent = oldInclude,
                pendingIncludeContent = candidateInclude,
                pendingChecksum = includes.checksum(candidateInclude)
            )
        }
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
            check(DeploymentApplicationService.prepareRuntimeCutover(job.id, previousDeployment?.id, existingSiteConfig?.content, durableCutoverFiles)) {
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
            val (containerPort, hostPort) = managedRoute ?: error("Managed site candidate route was not prepared")
            val previousHostPort = managedPreviousHostPort
            rollbackRoute = {
                if (convertsLegacySite) {
                    val restoredSite = previousConfig?.let { activeSiteService.restoreSiteConfiguration(ownerSlug, it) } == true
                    val restoredInclude = activeSiteService.restoreDeploymentUpstream(job.serviceId, previousHostPort)
                    val restoredKind = SiteRepository.restoreUpstreamKind(managedSite.id, managedSite.upstreamKind) != null
                    restoredSite && restoredInclude && restoredKind
                } else {
                    val restored = activeSiteService.restoreDeploymentUpstream(job.serviceId, previousHostPort)
                    if (restored) SiteRepository.restoreDeploymentUpstreamForSite(managedSite) != null else false
                }
            }
            onRouteRollback(rollbackRoute)

            if (recovery.state in setOf("restoring", "runtime_restored", "restore_failed")) {
                onRouteRollback(null)
                restorePreviousRuntime(job.id, previousDeployment, previousConfig, docker, activeSiteService, ownerSlug, managedSite)
            }
            val routeActivated = if (convertsLegacySite) {
                activeSiteService.convertSiteToDeploymentManaged(managedSite, hostPort)
            } else {
                activeSiteService.switchDeploymentUpstream(job.serviceId, ownerSlug, "127.0.0.1", containerPort, hostPort)
            }
            check(routeActivated) {
                "Gateway/site upstream cutover failed; previous runtime remains active"
            }
            check(DeploymentApplicationService.updateCutoverState(job.id, "route_switched")) { "Unable to persist route cutover state" }
            check(DeploymentApplicationService.updateCutoverState(job.id, "draining")) { "Unable to persist deployment drain state" }
            if (AppConfig.deploymentDrainDelaySeconds > 0) Thread.sleep(TimeUnit.SECONDS.toMillis(AppConfig.deploymentDrainDelaySeconds))
            val postDrain = activeSiteService.verifyAfterDrain(
                job.serviceId, managedSite.domain,
                managedSite.tlsMode != com.gatekeeper.db.tables.TlsMode.HTTP_ONLY,
                previousHostPort
            )
            if (!postDrain.candidateHealthy) {
                if (!postDrain.previousRouteRestored) DeploymentApplicationService.updateCutoverState(job.id, "repair_required", "Post-drain probe failed and previous upstream restoration failed")
                check(postDrain.previousRouteRestored) { "Post-drain probe failed and the previous route could not be restored; service requires repair" }
                error("Candidate failed its post-drain nginx probe; previous route was restored")
            }
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
        if (changesProductionRoute) {
            runCatching { ServiceDomainRepository.reconcilePorts(job.serviceId, runtime.ports.keys) }
                .onFailure { logger.warn("Unable to reconcile domain bindings for service {} after deployment {}", job.serviceId, job.id, it) }
        }
        update(
            job,
            step = "active",
            log = if (hasEnabledManagedSite) "Gateway switched to candidate; previous runtime retired" else "Deployment activated; Nginx site creation/activation remains a separate action",
            status = "succeeded"
        )
        DeploymentApplicationService.rollbackTargetId(job.id)?.let { targetId ->
            auditRepository.write(null, "deployment_rollback_activated", "deployment-worker", "deployment=${job.id} rolled_back_to=$targetId")
        }
        if (previousContainer != null && previousContainer != candidateRuntimeName) {
            previousDeployment?.let { previous ->
                auditRepository.write(null, "deployment_runtime_superseded", "deployment-worker", "deployment=${previous.id} container=$previousContainer")
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
                if (site?.upstreamKind == "DEPLOYMENT_MANAGED" && nginx != null) {
                    previous?.let { old ->
                        val oldHostPort = old.containerPort?.let(old.ports::get) ?: old.hostPort
                        site.serviceId?.let { nginx.restoreDeploymentUpstream(it, oldHostPort) } ?: false
                    } ?: false
                } else {
                    val routeRestored = if (nginx != null && ownerSlug != null) nginx.restoreSiteConfiguration(ownerSlug, config) else true
                    if (routeRestored && site != null) SiteRepository.restoreDeploymentUpstreamForSite(site) != null else routeRestored
                }
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

    private fun awaitHealthy(docker: DockerService, name: String, portMappings: Map<Int, Int>, job: DeploymentJobRecord): ReadinessResult? {
        return DeploymentReadiness.await(
            timeoutNanos = job.readinessTimeoutSeconds * 1_000_000_000L,
            intervalMillis = job.readinessIntervalSeconds * 1000L,
            checkCancelled = { ensureNotCancelled(job.id, requireNotNull(job.claimToken)) },
            probe = { readinessChecker.check(docker, name, portMappings, job) },
            pause = { intervalMillis -> Thread.sleep(intervalMillis) }
        )
    }

    private fun String.publishedHostPort(containerPort: Int): Int? = split(',').firstNotNullOfOrNull { mapping ->
        val parts = mapping.trim().substringBefore('/').split(':')
        parts.takeIf { it.size == 2 && it[1].toIntOrNull() == containerPort }
            ?.first()?.toIntOrNull()
    }

    private fun registryImage(registry: String, name: String, tag: String): String {
        val host = registry.trim().trimEnd('/')
        return if (host.isBlank() || host == "docker.io") "$name:$tag" else "$host/$name:$tag"
    }

}
