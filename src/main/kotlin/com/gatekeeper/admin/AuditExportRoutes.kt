package com.gatekeeper.admin

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.get
import java.util.*

fun Route.registerAuditExportRoutes(dependencies: OperationsAdminDependencies) {
    val auditRepository = dependencies.auditRepository

    get("/api/admin/audit/export") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 5000) ?: 1000
        val action = call.request.queryParameters["action"]
        val actor = call.request.queryParameters["actor"]
        val rows = auditRepository.findFiltered(action, actor, limit)
        val csv = buildString {
            appendLine("id,project_id,action,actor,reason,created_at")
            rows.forEach { row ->
                appendLine(
                    listOf(
                        row.id,
                        row.projectId ?: "",
                        row.action,
                        row.actor,
                        row.reason ?: "",
                        row.createdAt
                    ).joinToString(",") { csvEscape(it.toString()) })
            }
        }
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=gatekeeper-audit.csv")
        call.respondText(csv, ContentType.Text.CSV)
    }
}
