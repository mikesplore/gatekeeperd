package com.gatekeeper.integrations

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ScribedPayloadSerializationTest {
    @Test
    fun `receipt document uses the strict Scribed receipt request fields`() {
        val payload = ScribedIntegrationClient.ReceiptRequest(
            client_name = "Example Client", client_email = "client@example.com", project_name = "Project",
            description = "Payment received", amount = "100.00", currency = "KES",
            received_date = "2026-11-03", payment_method = "mpesa", payment_reference = "receipt-1"
        )

        val serialized = Json.encodeToString(payload).let(Json::parseToJsonElement).jsonObject

        assertEquals(
            setOf("client_name", "client_email", "project_name", "description", "amount", "currency", "received_date", "payment_method", "payment_reference"),
            serialized.keys
        )
    }

    @Test
    fun `client registration profile matches Scribed's snake case fields`() {
        val payload = ScribedIntegrationClient.ScribedClientProfile(
            name = "Gatekeeperd", address = "Main Street", contact_email = "ops@example.com", phone = "+254700000000"
        )
        val serialized = Json.encodeToString(payload).let(Json::parseToJsonElement).jsonObject
        assertEquals(setOf("name", "address", "contact_email", "phone"), serialized.keys)
    }
}
