package com.gatekeeper.admin

import com.gatekeeper.nginx.NginxEnableRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NginxAdminRoutesTest {
    @Test
    fun `empty body is allowed for DB driven site`() {
        assertFalse(hasRenderAffectingNginxParameters(NginxEnableRequest()))
    }

    @Test
    fun `render affecting body is rejected for DB driven site`() {
        assertTrue(hasRenderAffectingNginxParameters(NginxEnableRequest(port = 8080)))
        assertTrue(hasRenderAffectingNginxParameters(NginxEnableRequest(domain = "override.example.com")))
    }
}
