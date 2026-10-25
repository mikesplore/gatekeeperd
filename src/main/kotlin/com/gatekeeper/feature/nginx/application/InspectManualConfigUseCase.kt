package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.domain.service.NginxConflictAnalyzer
import com.gatekeeper.feature.nginx.model.DomainRules
import com.gatekeeper.feature.nginx.presentation.NginxConfigClassification
import com.gatekeeper.feature.nginx.presentation.NginxManualConfigDetail
import java.io.File
import java.nio.file.Files

class InspectManualConfigUseCase(
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
    private val sitesEnabledPath: String = "/etc/nginx/sites-enabled",
    private val domainRules: DomainRules,
    private val nginxConflictAnalyzer: NginxConflictAnalyzer
) {
    fun execute(filename: String): NginxManualConfigDetail = inspectManualConfig(
        filename,
        sitesAvailablePath = sitesAvailablePath,
        sitesEnabledPath = sitesEnabledPath
    )

    fun inspectManualConfig(
        filename: String, sitesAvailablePath: String = "/etc/nginx/sites-available",
        sitesEnabledPath: String = "/etc/nginx/sites-enabled"
    ): NginxManualConfigDetail {
        require(domainRules.isSafeConfigFilename(filename)) { "Invalid nginx config filename" }
        val available = File(sitesAvailablePath, filename)
        val enabled = File(sitesEnabledPath, filename)
        require(
            !domainRules.isExcluded(
                available,
                enabled
            )
        ) { "This nginx config serves Gatekeeperd and is excluded from site management" }
        val source = available.takeIf { it.isFile } ?: enabled.takeIf { it.isFile || Files.isSymbolicLink(it.toPath()) }
        ?: throw IllegalArgumentException("Nginx config not found")
        val content = source.readText()
        require(nginxConflictAnalyzer.classifyConfig(filename, content).classification == NginxConfigClassification.MANUAL) {
            "Only unmarked manual nginx configs can be inspected here"
        }
        val classified = nginxConflictAnalyzer.classifyConfig(filename, content)
        return NginxManualConfigDetail(
            filename, classified.serverNames, classified.listenPorts,
            available.isFile, enabled.exists() || Files.isSymbolicLink(enabled.toPath()), content
        )
    }
}
