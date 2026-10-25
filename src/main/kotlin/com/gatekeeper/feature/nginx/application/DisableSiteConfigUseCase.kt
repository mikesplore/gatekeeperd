package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.domain.port.NginxCommandRunner
import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage

class DisableSiteConfigUseCase(
    private val storage: NginxSiteStorage,
    private val commandRunner: NginxCommandRunner
) {
    fun execute(slug: String): Boolean {
        val disabled = storage.disableSite(slug)
        return disabled && commandRunner.reload()
    }
}