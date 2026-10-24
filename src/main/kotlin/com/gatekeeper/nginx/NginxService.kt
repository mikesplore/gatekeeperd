package com.gatekeeper.nginx

import com.gatekeeper.config.AppSettings
import com.gatekeeper.core.ports.CutoverProbe
import com.gatekeeper.core.ports.PortProbe
import com.gatekeeper.feature.nginx.domain.port.NginxCommandRunner
import com.gatekeeper.feature.nginx.infrastructure.NginxSelfDomainExclusion
import com.gatekeeper.feature.nginx.presentation.NginxTestResult
import com.gatekeeper.infrastructure.TcpCutoverProbe
import com.gatekeeper.infrastructure.TcpPortProbe


class NginxService(
    private val sitesAvailablePath: String = "/etc/nginx/sites-available",
    private val sitesEnabledPath: String = "/etc/nginx/sites-enabled",
    private val gatekeeperPort: Int = 8080,
    private val sslCertPath: String = "/etc/letsencrypt/live",
    private val nginxTestRunner: () -> NginxTestResult = { NginxCommandRunner().test() },
    private val nginxReloadRunner: () -> Boolean = { NginxCommandRunner().reload() },
    private val selfDomainExclusion: NginxSelfDomainExclusion = NginxSelfDomainExclusion(emptyList()),
    private val upstreamIncludeDirectory: String = "/etc/nginx/conf.d/gatekeeperd-upstreams",
    private val cutoverProbe: CutoverProbe = TcpCutoverProbe(),
    private val localProbeRunner: (String, Boolean) -> Boolean = { domain, tls ->
        cutoverProbe.throughLocalNginx(
            domain,
            tls
        )
    },

    ) {
    companion object {
        fun configured(
            settings: AppSettings.NginxSettings,
            commands: NginxCommandRunner,
            portProbe: PortProbe = TcpPortProbe(),
            cutoverProbe: CutoverProbe = TcpCutoverProbe()
        ): NginxService = NginxService(
            sitesAvailablePath = settings.sitesAvailablePath,
            sitesEnabledPath = settings.sitesEnabledPath,
            sslCertPath = settings.sslCertPath,
            upstreamIncludeDirectory = settings.upstreamIncludeDirectory,
            selfDomainExclusion = NginxSelfDomainExclusion(settings.selfDomains),
            cutoverProbe = cutoverProbe,
            nginxTestRunner = commands::test,
            nginxReloadRunner = commands::reload,
            nginxConfigDumpRunner = commands::dumpConfig,
            portProbe = portProbe,
            processRunner = commands.processRunner,
        )
    }

}