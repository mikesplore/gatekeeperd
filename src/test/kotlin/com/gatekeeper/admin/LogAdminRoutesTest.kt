package com.gatekeeper.admin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogAdminRoutesTest {
    private val json = Json

    @Test
    fun `parses structured journal messages and redacts credentials`() {
        val message = """{"@timestamp":"2026-10-14T10:00:00Z","level":"ERROR","logger_name":"com.example.Auth","thread_name":"worker-1","message":"Request failed password=hunter2 authorization: Bearer abc123 jdbc:postgresql://admin:dbsecret@localhost/app","requestId":"req-1","stack_trace":"token=xyz987"}"""
        val line = """{"MESSAGE":${json.encodeToString(message)},"__CURSOR":"cursor-1"}"""

        val entry = JournalLogReader.parseRecord(line)

        assertEquals("cursor-1", entry?.id)
        assertEquals("ERROR", entry?.level)
        assertEquals("2026-10-14T10:00:00Z", entry?.timestamp)
        assertFalse(entry!!.message.contains("hunter2"))
        assertFalse(entry.message.contains("abc123"))
        assertFalse(entry.message.contains("dbsecret"))
        assertFalse(entry.stackTrace!!.contains("xyz987"))
        assertTrue(entry.message.contains("[REDACTED]"))
    }

    @Test
    fun `summarizes request outcomes and log severities`() {
        val entries = listOf(
            AdminLogEntry("1", "2026-10-14T10:00:00Z", "INFO", "io.ktor.server.Application", message = "200 OK: GET - /api/health in 2ms"),
            AdminLogEntry("2", "2026-10-14T10:00:01Z", "INFO", "io.ktor.server.Application", message = "503 Service Unavailable: POST - /api/auth/login in 8ms"),
            AdminLogEntry("3", "2026-10-14T10:00:02Z", "ERROR", "com.example.Auth", message = "login failed"),
            AdminLogEntry("4", "2026-10-14T10:00:03Z", "WARN", "com.example.Redis", message = "redis retry")
        )

        val summary = JournalLogReader.summary(entries)

        assertEquals(4, summary.scanned)
        assertEquals(1, summary.errorLogs)
        assertEquals(1, summary.warningLogs)
        assertEquals(2, summary.requestCount)
        assertEquals(1, summary.successfulRequests)
        assertEquals(1, summary.failedRequests)
        assertEquals(50.0, summary.successRate)
        assertEquals(50.0, summary.errorRate)
    }

    @Test
    fun `filters by level and searches all displayed fields`() {
        val entry = AdminLogEntry("1", "2026-10-14T10:00:00Z", "ERROR", "com.example.Auth", message = "invalid credential", requestId = "request-42")

        assertTrue(JournalLogReader.matches(entry, "request-42", "error"))
        assertFalse(JournalLogReader.matches(entry, "missing", "ERROR"))
        assertFalse(JournalLogReader.matches(entry, null, "WARN"))
    }
}
