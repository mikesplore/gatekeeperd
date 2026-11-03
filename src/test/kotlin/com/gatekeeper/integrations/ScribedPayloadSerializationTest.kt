package com.gatekeeper.integrations

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ScribedPayloadSerializationTest {
    @Test
    fun `service payment sync uses Scribed's strict item schema`() {
        val payload = ScribedServicePaymentSyncPayload(
            project_id = "project-1",
            service_id = "service-1",
            currency = "KES",
            payments = listOf(
                ScribedServicePaymentPayload(
                    gatekeeper_payment_id = "payment-1",
                    provider = "mpesa",
                    provider_reference = "receipt-1",
                    amount = "100.00",
                    currency = "KES",
                    paid_at = "2026-11-03T12:00:00"
                )
            )
        )

        val serialized = Json.encodeToString(payload).let(Json::parseToJsonElement).jsonObject
        val payment = serialized.getValue("payments").jsonArray.single().jsonObject

        assertEquals(setOf("project_id", "service_id", "currency", "payments"), serialized.keys)
        assertEquals(
            setOf("gatekeeper_payment_id", "provider", "provider_reference", "amount", "currency", "paid_at"),
            payment.keys
        )
    }
}
