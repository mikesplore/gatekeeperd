package com.gatekeeper.nginx

import com.gatekeeper.core.ports.CutoverProbe
import com.gatekeeper.feature.nginx.domain.port.NginxCommandRunner
import com.gatekeeper.feature.nginx.infrastructure.NginxSelfDomainExclusion
import com.gatekeeper.feature.nginx.presentation.NginxTestResult

/**Dont call this service anywhere**/
class NginxService(
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
    private val sitesEnabledPath: String = "/etc/nginx/sites-enabled",
    private val gatekeeperPort: Int = 8080,
    private val sslCertPath: String = "/etc/letsencrypt/live",
    private val nginxTestRunner: () -> NginxTestResult = { NginxCommandRunner().test() },
    private val nginxReloadRunner: () -> Boolean = { NginxCommandRunner().reload() },
    private val selfDomainExclusion: NginxSelfDomainExclusion = NginxSelfDomainExclusion(emptyList()),
    private val upstreamIncludeDirectory: String = "/etc/nginx/conf.d/gatekeeperd-upstreams",
    private val cutoverProbe: CutoverProbe,
    private val localProbeRunner: (String, Boolean) -> Boolean = { domain, tls ->
        cutoverProbe.throughLocalNginx(
            domain,
            tls
        )
    }

)