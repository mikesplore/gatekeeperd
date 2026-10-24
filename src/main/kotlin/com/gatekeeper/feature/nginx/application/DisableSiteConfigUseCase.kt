package com.gatekeeper.feature.nginx.application

import com.gatekeeper.feature.nginx.domain.port.NginxSiteStorage
import com.gatekeeper.nginx.NginxCommandRunner

class DisableSiteConfigUseCase(
    private val storage: NginxSiteStorage,
    private val commandRunner: NginxCommandRunner
) {
    fun execute(slug: String): Boolean {
        val disabled = storage.disableSite(slug)
        return disabled && commandRunner.reload()
    }
}