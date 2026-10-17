package com.gatekeeper.config

import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {
    @Test
    fun `environment overrides dotenv values`() {
        val settings = AppSettings.fromEnv(
            mapOf(
                "DB_URL" to "jdbc:test",
                "PORT" to "9090"
            )
        )

        assertEquals("jdbc:test", settings["DB_URL"])
        assertEquals("9090", settings["PORT"])
    }

    @Test
    fun `blank values are not exposed`() {
        assertEquals(null, AppSettings.fromEnv(mapOf("EMPTY_SETTING" to "  "))["EMPTY_SETTING"])
    }
}
