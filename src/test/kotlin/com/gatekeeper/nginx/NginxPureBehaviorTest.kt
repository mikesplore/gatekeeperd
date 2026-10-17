package com.gatekeeper.nginx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NginxPureBehaviorTest {
    @Test
    fun `upstream parsing handles supported docker forms`() {
        listOf(
            "9921->9921/tcp" to mapOf(9921 to 9921),
            "0.0.0.0:32768->9921/tcp, 8443:443/tcp" to mapOf(9921 to 32768, 443 to 8443),
            "9921/tcp" to emptyMap()
        ).forEach { (input, expected) ->
            assertEquals(expected, UpstreamValidation.parsePublishedPorts(input))
        }
    }

    @Test
    fun `renderer and parser preserve managed blocks`() {
        val config = NginxConfigRenderer().render(
            slug = "acw",
            domain = "acw.example.com",
            appPort = 3001,
            upstreamScheme = "http",
            sslEnabled = false
        )

        assertTrue(config.contains("proxy_pass http://127.0.0.1:3001;"))
        assertEquals(
            listOf("server", "bypass", "upstream", "auth_request", "paywall"),
            NginxConfigParser.parseBlocks(config).map { it.type }
        )
    }
}
