package com.gatekeeper.feature.nginx.application

import com.gatekeeper.core.ports.CutoverProbe
import com.gatekeeper.feature.nginx.domain.port.NginxCommandRunner
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamIncludes
import com.gatekeeper.feature.nginx.model.nginxServiceLogger
import com.gatekeeper.feature.nginx.presentation.NginxTestResult
import com.gatekeeper.infrastructure.TcpCutoverProbe
import com.gatekeeper.plugins.DistributedLock
import java.io.File

class RepairDeploymentUpstreamUseCase(
    private val upstreamIncludeDirectory: String = "/etc/nginx/conf.d/gatekeeperd-upstreams"
) {
    fun execute(
        serviceId: java.util.UUID,
        domain: String, tls: Boolean,
        port: Int,
        nginxReloadRunner: () -> Boolean = { NginxCommandRunner().reload() },
        nginxTestRunner: () -> NginxTestResult = { NginxCommandRunner().test() },
        cutoverProbe: CutoverProbe = TcpCutoverProbe(),
        localProbeRunner: (String, Boolean) -> Boolean = { domain, tls ->
            cutoverProbe.throughLocalNginx(
                domain,
                tls
            )
        }
    ): Boolean {
        return DistributedLock.withLock("nginx-host-mutation") {
            val includes = DeploymentUpstreamIncludes(File(upstreamIncludeDirectory))
            val previous = includes.file(serviceId).takeIf { it.isFile }?.readText()
            try {
                includes.write(serviceId, includes.render(serviceId, port))
                check(nginxTestRunner().valid && nginxReloadRunner())
                check(localProbeRunner(domain, tls))
                true
            } catch (error: Exception) {
                runCatching { restoreUpstreamInclude(includes, serviceId, previous) }
                nginxServiceLogger.error("Explicit nginx upstream repair failed for service {}", serviceId, error)
                false
            }
        }
    }
}
