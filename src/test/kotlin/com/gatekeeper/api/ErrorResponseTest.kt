package com.gatekeeper.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorResponseTest {
    @Test
    fun `unwraps Docker status JSON into a readable volume conflict`() {
        val response = errorResponse(
            "volume_delete_failed",
            "Status 409: {\"message\":\"remove weusifixvolume: volume is in use - [379caf]\"}"
        )

        assertEquals(
            "This volume is still attached to one or more containers. Stop or remove those containers before deleting it.",
            response.message
        )
    }

    @Test
    fun `preserves ordinary API error messages`() {
        assertEquals("Project not found", errorResponse("project_not_found", "Project not found").message)
    }
}
