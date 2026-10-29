package com.gatekeeper.admin

import com.gatekeeper.feature.nginx.presentation.NginxEnableRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class NginxAdminRoutesTest {
    @Test
    fun `enable request selects service domain binding and requires ssl by default`() {
        val request = NginxEnableRequest(
            serviceId = "b0ee7a11-1a47-4fcb-9325-4e6c704f0311",
            domainBindingId = "9f6fcb7a-625a-4bc5-8827-56ff516eed55"
        )

        assertEquals("b0ee7a11-1a47-4fcb-9325-4e6c704f0311", request.serviceId)
        assertEquals("9f6fcb7a-625a-4bc5-8827-56ff516eed55", request.domainBindingId)
        assertEquals(true, request.requireSsl)
    }
}
