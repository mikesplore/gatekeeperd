package com.gatekeeper.nginx

import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import com.gatekeeper.feature.nginx.infrastructure.DeploymentUpstreamIncludes
import com.gatekeeper.feature.nginx.infrastructure.NginxConfigRenderer
import com.gatekeeper.feature.nginx.model.NginxSiteRenderModel
import com.gatekeeper.feature.nginx.model.TlsRenderMode
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NginxServiceTest {
    @Test
    fun `renderer emits gated legacy upstream config`() {
        val config = renderer().render(
            slug = "acw",
            domain = "acw.example.com",
            appPort = 3001,
            upstreamScheme = "http",
            sslEnabled = false
        )

        assertContains(config, "proxy_pass http://127.0.0.1:3001;")
        assertContains(config, "auth_request /gatekeeper-auth-acw;")
        assertContains(config, "listen 80;")
    }

    @Test
    fun `managed deployment uses a stable service upstream`() {
        val serviceId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val config = renderer().render(
            NginxSiteRenderModel(
                slug = "acw",
                serviceId = serviceId,
                domain = "acw.example.com",
                appPort = 32123,
                upstreamScheme = "http",
                tlsMode = TlsRenderMode.HTTP_ONLY,
                upstreamKind = "DEPLOYMENT_MANAGED"
            )
        )

        assertContains(config, "proxy_pass http://gk_123e4567e89b12d3a456426614174000;")
        assertContains(config, "location = /.well-known/gatekeeperd-cutover-probe")
        assertContains(config, "deny all;")
    }

    @Test
    fun `upstream include is atomically written and checksummed`() {
        val directory = Files.createTempDirectory("gk-upstreams").toFile()
        val serviceId = UUID.randomUUID()
        val includes = DeploymentUpstreamIncludes(directory)
        val rendered = includes.render(serviceId, 32123)

        includes.write(serviceId, rendered)

        assertEquals(rendered, includes.file(serviceId).readText())
        assertEquals(includes.checksum(rendered), includes.checksum(includes.file(serviceId).readText()))
        assertFailsWith<IllegalArgumentException> { includes.render(serviceId, 0) }
    }

    private fun renderer() = NginxConfigRenderer(
        gatekeeperUpstream = "127.0.0.1:8080",
        sslCertPath = Files.createTempDirectory("gk-certificates").toFile().absolutePath,
        nginxValidation = NginxValidationService()
    )
}
