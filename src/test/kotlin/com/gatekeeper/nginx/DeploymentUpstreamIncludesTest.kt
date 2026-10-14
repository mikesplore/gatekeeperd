package com.gatekeeper.nginx

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeploymentUpstreamIncludesTest {
    @Test
    fun `upstream name and include file are derived only from service id`() {
        val directory = Files.createTempDirectory("gk-upstreams").toFile()
        val serviceId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        val includes = DeploymentUpstreamIncludes(directory)

        assertEquals("gk_123e4567e89b12d3a456426614174000", includes.upstreamName(serviceId))
        assertEquals("$serviceId.conf", includes.file(serviceId).name)
        val rendered = includes.render(serviceId, 32123)
        includes.write(serviceId, rendered)
        assertEquals(rendered, includes.file(serviceId).readText())
        assertEquals(includes.checksum(rendered), includes.checksum(includes.file(serviceId).readText()))
        assertNotEquals(includes.checksum(rendered), includes.checksum(includes.render(serviceId, 32124)))
    }
}
