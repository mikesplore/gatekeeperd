package com.gatekeeper.feature.audit.domain.repository

import java.time.LocalDateTime
import java.util.UUID

interface AuditRepository {
    enum class SortDirection { ASC, DESC }
    data class Record(val id: UUID, val projectId: UUID?, val action: String, val actor: String, val reason: String?, val createdAt: LocalDateTime)
    fun write(projectId: UUID?, action: String, actor: String, reason: String?)
    fun findByProjectId(projectId: UUID): List<Record>
    fun findAll(limit: Int = 100): List<Record>
    fun count(query: String? = null, action: String? = null): Long
    fun findPage(limit: Int, offset: Int, query: String? = null, action: String? = null, sort: String = "createdAt", direction: SortDirection = SortDirection.DESC): List<Record>
    fun findFiltered(action: String?, actor: String?, limit: Int = 1000): List<Record>
}
