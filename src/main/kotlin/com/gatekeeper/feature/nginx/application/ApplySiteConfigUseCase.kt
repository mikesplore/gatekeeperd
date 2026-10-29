package com.gatekeeper.feature.nginx.application

import com.gatekeeper.core.util.sha256
import com.gatekeeper.feature.nginx.domain.port.NginxCommandRunner
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.feature.nginx.domain.service.NginxConflictAnalyzer
import com.gatekeeper.feature.nginx.infrastructure.NginxSelfDomainExclusion
import com.gatekeeper.feature.nginx.model.DomainRules
import com.gatekeeper.feature.nginx.model.NginxSiteActivationResult
import com.gatekeeper.feature.nginx.model.nginxServiceLogger
import com.gatekeeper.plugins.DistributedLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

class ApplySiteConfigUseCase(
    private val commandRunner: NginxCommandRunner,
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
    private val sitesEnabledPath: String = "/etc/nginx/sites-enabled",
    private val nginxSiteStorage: NginxSiteStorage,
    private val selfDomainExclusion: NginxSelfDomainExclusion,
    private val domainRules: DomainRules,
    private val nginxConflictAnalyzer: NginxConflictAnalyzer
) {
    fun execute(slug: String, configContent: String): NginxSiteActivationResult {
        val previousConfig = nginxSiteStorage.readAvailable(slug)
        if (previousConfig != null) {
            nginxSiteStorage.createBackup(slug, previousConfig)
        }

        nginxSiteStorage.writeAvailable(slug, configContent)
        val testResult = commandRunner.test()
        if (testResult.exitCode != 0) {
            if (previousConfig != null) nginxSiteStorage.writeAvailable(
                slug,
                previousConfig
            ) else nginxSiteStorage.removeSite(slug)
            return NginxSiteActivationResult(success = false, message = "Nginx validation failed: ${testResult.output}")
        }

        val enabled = nginxSiteStorage.enableSite(slug)
        if (!enabled) {
            return NginxSiteActivationResult(success = false, message = "Failed to create enabled symlink")
        }

        val reloaded = commandRunner.reload()
        if (!reloaded) {
            if (previousConfig != null) {
                nginxSiteStorage.writeAvailable(slug, previousConfig)
                nginxSiteStorage.enableSite(slug)
                commandRunner.reload()
            }
            return NginxSiteActivationResult(success = false, message = "Nginx reload failed")
        }

        return NginxSiteActivationResult(success = true)
    }

    fun recordManagedVersion(slug: String, content: String) {
        Files.writeString(File(sitesAvailablePath, ".$slug.gatekeeperd.sha256").toPath(), sha256(content))
    }

    fun enableProject(slug: String, configContent: String): Boolean {
        return enableProjectDetailed(slug, configContent).success
    }

    fun enableProjectDetailed(slug: String, configContent: String): NginxSiteActivationResult {
        nginxServiceLogger.info("Enabling nginx site: $slug")
        return DistributedLock.withLock("nginx-host-mutation") {
            try {
                val conflicts = try {
                    nginxConflictAnalyzer.findDomainConflicts(
                        configContent,
                        slug,
                        nginxSiteStorage = nginxSiteStorage,
                        nginxSelfDomainExclusion = selfDomainExclusion,
                        domainRules = domainRules
                    )
                } catch (e: Exception) {
                    nginxServiceLogger.error("Failed to analyze domain conflicts for site $slug", e)
                    return@withLock NginxSiteActivationResult(
                        false,
                        "Conflict analysis failed: ${e.message ?: "unknown error"}"
                    )
                }

                if (conflicts.isNotEmpty()) {
                    val message =
                        "Nginx domain conflict: ${conflicts.joinToString { it.filename ?: "Gatekeeperd self domain" }}"
                    nginxServiceLogger.warn("Refusing to enable nginx site $slug because it conflicts with ${conflicts.joinToString { it.filename ?: "Gatekeeperd self domain" }}")
                    return@withLock NginxSiteActivationResult(false, message)
                }
                val availableFile = File("$sitesAvailablePath/$slug")
                val enabledFile = File("$sitesEnabledPath/$slug")
                if (domainRules.isExcluded(availableFile, enabledFile)) {
                    nginxServiceLogger.warn("Refusing to manage nginx site $slug because its existing config serves Gatekeeperd")
                    return@withLock NginxSiteActivationResult(
                        false,
                        "This config is excluded from Gatekeeperd management"
                    )
                }
                try {
                    availableFile.parentFile?.mkdirs()
                    enabledFile.parentFile?.mkdirs()
                } catch (e: Exception) {
                    nginxServiceLogger.error("Failed to create parent directories for site $slug", e)
                    return@withLock NginxSiteActivationResult(
                        false,
                        "Failed to create directories: ${e.message ?: "unknown error"}"
                    )
                }

                val stagedFile = File(availableFile.parent, ".${slug}.staged")
                try {
                    Files.deleteIfExists(stagedFile.toPath())
                    Files.writeString(stagedFile.toPath(), configContent)
                } catch (e: Exception) {
                    nginxServiceLogger.error("Failed to write staged file for site $slug", e)
                    return@withLock NginxSiteActivationResult(
                        false,
                        "Failed to write staged configuration: ${e.message ?: "unknown error"}"
                    )
                }

                // Validate the staged site through nginx's normal include tree while
                // leaving the live site and its enabled link untouched.
                val validation = try {
                    nginxSiteStorage.testNginxConfigWithStagedSite(slug, stagedFile)
                } catch (e: Exception) {
                    nginxServiceLogger.error("Exception during nginx test invocation for site $slug", e)
                    Files.deleteIfExists(stagedFile.toPath())
                    return@withLock NginxSiteActivationResult(
                        false,
                        "Nginx validation execution failed: ${e.message ?: "unknown error"}"
                    )
                }

                if (!validation.valid) {
                    Files.deleteIfExists(stagedFile.toPath())
                    val detail = validation.output.trim().take(3000)
                        .ifBlank { "nginx -t exited with code ${validation.exitCode}" }
                    nginxServiceLogger.error(
                        "Nginx validation failed for staged site $slug (exit {}): {}",
                        validation.exitCode,
                        detail
                    )
                    return@withLock NginxSiteActivationResult(false, "nginx -t failed: $detail")
                }

                val backup = try {
                    if (availableFile.isFile) {
                        File(availableFile.parent, "$slug.bak-${Instant.now().toEpochMilli()}").also {
                            Files.copy(availableFile.toPath(), it.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                        }
                    } else null
                } catch (e: Exception) {
                    nginxServiceLogger.warn("Failed to create backup for site $slug, continuing without backup", e)
                    null
                }

                try {
                    try {
                        Files.move(
                            stagedFile.toPath(),
                            availableFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(stagedFile.toPath(), availableFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    nginxSiteStorage.replaceEnabledSymlink(availableFile, enabledFile)

                    if (!nginxSiteStorage.reloadNginx()) {
                        nginxSiteStorage.restoreActivatedSite(availableFile, enabledFile, backup)
                        try {
                            nginxSiteStorage.reloadNginx()
                        } catch (e: Exception) {
                            nginxServiceLogger.error("Failed to reload nginx during rollback for $slug", e)
                        }
                        nginxServiceLogger.error("Nginx reload failed for $slug; previous configuration was restored")
                        return@withLock NginxSiteActivationResult(
                            false,
                            "Nginx reload failed after validation; the previous configuration was restored"
                        )
                    }
                } catch (e: Exception) {
                    nginxServiceLogger.error("Exception during file activation or reload for site $slug", e)
                    try {
                        nginxSiteStorage.restoreActivatedSite(availableFile, enabledFile, backup)
                    } catch (ex: Exception) {
                        nginxServiceLogger.error(
                            "Failed to restore activated site during exception handling for $slug",
                            ex
                        )
                    }
                    try {
                        nginxSiteStorage.reloadNginx()
                    } catch (ex: Exception) {
                        nginxServiceLogger.error("Failed to reload nginx during exception handling for $slug", ex)
                    }
                    return@withLock NginxSiteActivationResult(
                        false,
                        "Nginx activation failed and the previous configuration was restored: ${e.message ?: "unknown error"}"
                    )
                } finally {
                    try {
                        Files.deleteIfExists(stagedFile.toPath())
                    } catch (e: Exception) {
                        nginxServiceLogger.warn("Failed to clean up staged file for site $slug", e)
                    }
                }

                // Read the activated file rather than hashing the input so drift
                // tracking always reflects the exact bytes now on disk.
                try {
                    recordManagedVersion(slug, availableFile.readText())
                } catch (e: Exception) {
                    nginxServiceLogger.warn("Failed to record managed version for site $slug", e)
                }

                nginxServiceLogger.info("Enabled nginx site: $slug")
                NginxSiteActivationResult(true)
            } catch (e: Exception) {
                nginxServiceLogger.error("Failed to enable nginx site: $slug", e)
                NginxSiteActivationResult(false, "Failed to enable Nginx site: ${e.message ?: "unknown error"}")
            }
        }
    }

}