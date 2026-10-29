package com.gatekeeper.admin

import com.gatekeeper.feature.nginx.presentation.NginxEnableRequest
import com.gatekeeper.feature.nginx.presentation.nginxHasRenderAffectingParameters
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NginxAdminRoutesTest {
    @Test
    fun `empty body is allowed for DB driven site`() {
        assertFalse(nginxHasRenderAffectingParameters(NginxEnableRequest()))
    }

    @Test
    fun `render affecting body is rejected for DB driven site`() {
        assertTrue(nginxHasRenderAffectingParameters(NginxEnableRequest(port = 8080)))
        assertTrue(nginxHasRenderAffectingParameters(NginxEnableRequest(domain = "override.example.com")))
    }
}
