package com.gatekeeper.nginx

import com.gatekeeper.config.AppEnvironment
import com.gatekeeper.feature.nginx.domain.port.NginxEnvironmentPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class NginxDevelopmentPolicyTest {
    @Test
    fun `development config uses local nginx commands without sudo or systemd`() {
        val policy = NginxEnvironmentPolicy(
            environment = AppEnvironment.DEV,
            nginxHostSudoEnabled = true,
            nginxCertbotEnabled = false,
            customCertDirectory = null,
            nginxBinary = "/usr/local/sbin/nginx",
            developmentConfigPath = "nginx/nginx.conf"
        )

        assertEquals(
            listOf("/usr/local/sbin/nginx", "-t", "-c", "nginx/nginx.conf", "-p", "."),
            policy.nginxTestCommand()
        )
        assertEquals(
            listOf("/usr/local/sbin/nginx", "-s", "reload", "-c", "nginx/nginx.conf", "-p", "."),
            policy.nginxReloadCommand()
        )
    }
}
