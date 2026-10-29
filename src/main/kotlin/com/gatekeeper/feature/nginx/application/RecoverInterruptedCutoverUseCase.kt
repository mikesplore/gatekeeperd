package com.gatekeeper.feature.nginx.application

import com.gatekeeper.db.tables.TlsMode
import com.gatekeeper.deployment.DurableCutoverFiles
import com.gatekeeper.feature.nginx.domain.port.NginxCommandRunner
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamIncludes
import com.gatekeeper.feature.nginx.model.InterruptedCutoverResult
import com.gatekeeper.feature.nginx.model.nginxServiceLogger
import com.gatekeeper.feature.nginx.presentation.NginxTestResult
import com.gatekeeper.feature.service.domain.repository.SiteRecord
import com.gatekeeper.feature.service.domain.repository.SiteRepository
import com.gatekeeper.plugins.DistributedLock
import java.io.File
import java.nio.file.Files
import com.gatekeeper.feature.nginx.domain.port.NginxEnvironmentPolicy

class RecoverInterruptedCutoverUseCase(
    private val upstreamIncludeDirectory: String = "/etc/nginx/conf.d/gatekeeperd-upstreams",
    private val nginxTestRunner: () -> NginxTestResult,
    private val nginxReloadRunner: () -> Boolean,
    private val nginxSiteStorage: NginxSiteStorage,
    private val convertSiteToDeploymentManagedUseCase: ConvertSiteToDeploymentManagedUseCase,
    private val localProbeRunner: (String, Boolean) -> Boolean,
    private val siteRepository: SiteRepository,
) {
    fun execute(
        site: SiteRecord,
        files: DurableCutoverFiles,
        candidateRunning: Boolean
    ): InterruptedCutoverResult {
        return DistributedLock.withLock("nginx-host-mutation") {
            val serviceId = site.serviceId ?: return@withLock InterruptedCutoverResult.CONFLICT
            val domain = site.domain
            val tls = site.tlsMode != TlsMode.HTTP_ONLY
            val includes = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory))
            val file = includes.file(serviceId)
            val current = file.takeIf { it.isFile }?.readText()
            val pending = files.pendingIncludeContent ?: return@withLock InterruptedCutoverResult.CONFLICT
            val currentHash = current?.let(includes::checksum)
            val pendingMatches = currentHash == files.pendingChecksum && files.pendingChecksum == includes.checksum(pending)
            val previousMatches = current == files.previousIncludeContent
            if (pendingMatches && candidateRunning) {
                val restored = if (site.upstreamKind != "DEPLOYMENT_MANAGED") {
                    val targetPort = nginxSiteStorage.includePort(pending) ?: return@withLock InterruptedCutoverResult.CONFLICT
                    convertSiteToDeploymentManagedUseCase.execute(site, targetPort)
                } else {
                    nginxTestRunner().valid && nginxReloadRunner() && localProbeRunner(domain, tls)
                }
                if (restored) {
                    return@withLock InterruptedCutoverResult.TARGET_RELOADED
                }
            } else if (previousMatches) {
                if (files.previousUpstreamKind != "DEPLOYMENT_MANAGED") {
                    val slug = files.siteSlug ?: site.projectSlug
                    val configRestored = !(files.previousSiteConfig != null && slug != null) || nginxSiteStorage.restoreSiteConfiguration(slug, files.previousSiteConfig)
                    if (!configRestored) return@withLock InterruptedCutoverResult.CONFLICT
                    if (siteRepository.restoreUpstreamKind(site.id, files.previousUpstreamKind) == null) {
                        return@withLock InterruptedCutoverResult.CONFLICT
                    }
                }
                return@withLock InterruptedCutoverResult.PREVIOUS_RESTORED
            }
            if (!pendingMatches) return@withLock InterruptedCutoverResult.CONFLICT
            runCatching {
                if (files.previousUpstreamKind != "DEPLOYMENT_MANAGED") {
                    val slug = files.siteSlug ?: site.projectSlug
                    check(files.previousSiteConfig != null && slug != null)
                    check(nginxSiteStorage.restoreSiteConfiguration(slug, files.previousSiteConfig))
                    check(siteRepository.restoreUpstreamKind(site.id, files.previousUpstreamKind) != null)
                }
                if (files.previousIncludeContent == null) Files.deleteIfExists(file.toPath())
                else includes.write(serviceId, files.previousIncludeContent)
                check(nginxTestRunner().valid && nginxReloadRunner())
                check(localProbeRunner(domain, tls))
            }.fold(
                onSuccess = { InterruptedCutoverResult.PREVIOUS_RESTORED },
                onFailure = { error ->
                    nginxServiceLogger.error("Interrupted nginx cutover could not restore service {}", serviceId, error)
                    InterruptedCutoverResult.CONFLICT
                }
            )
        }
    }
}
