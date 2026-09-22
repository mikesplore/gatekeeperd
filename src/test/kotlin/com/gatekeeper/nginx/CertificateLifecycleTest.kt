package com.gatekeeper.nginx

import com.gatekeeper.db.repositories.CertificateRepository
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CertificateLifecycleTest {
    @Test
    fun `certificate removal is rejected while an active site is linked`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            CertificateRepository.requireRemovable("example.com", linkedActiveSites = 1)
        }
        assertTrue(failure.message!!.contains("still referenced by active sites"))
    }
}
