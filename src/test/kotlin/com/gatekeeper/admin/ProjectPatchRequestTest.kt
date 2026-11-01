package com.gatekeeper.admin

import com.gatekeeper.admin.model.UpdateProjectRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectPatchRequestTest {
    private val json = Json

    @Test
    fun `patch DTO distinguishes omitted fields from explicit null`() {
        val omitted = json.decodeFromString<UpdateProjectRequest>("{}")
        assertFalse(omitted.customerId.isSpecified)
        assertFalse(omitted.amountDue.isSpecified)
        assertFalse(omitted.dueDate.isSpecified)

        val explicitNulls = json.decodeFromString<UpdateProjectRequest>(
            """{"customerId":null,"amountDue":null,"dueDate":null}"""
        )
        assertTrue(explicitNulls.customerId.isSpecified)
        assertTrue(explicitNulls.amountDue.isSpecified)
        assertTrue(explicitNulls.dueDate.isSpecified)
        assertEquals(null, explicitNulls.customerId.value)
        assertEquals(null, explicitNulls.amountDue.value)
        assertEquals(null, explicitNulls.dueDate.value)
    }

    @Test
    fun `patch DTO reads structured values`() {
        val request = json.decodeFromString<UpdateProjectRequest>(
            """{"customerId":"123e4567-e89b-12d3-a456-426614174000","amountDue":25.5,"dueDate":"2026-12-31"}"""
        )
        assertTrue(request.customerId.isSpecified)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", request.customerId.value)
        assertEquals(25.5, request.amountDue.value)
        assertEquals("2026-12-31", request.dueDate.value)
    }
}
