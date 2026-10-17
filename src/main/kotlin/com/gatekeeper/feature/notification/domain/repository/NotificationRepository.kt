package com.gatekeeper.feature.notification.domain.repository

import java.time.LocalDateTime
import java.util.UUID

interface NotificationRepository {
    data class Record(
        val id: UUID,
        val recipient: String,
        val projectId: UUID?,
        val title: String,
        val message: String,
        val severity: String,
        val action: String,
        val createdAt: LocalDateTime,
        val readAt: LocalDateTime?,
        val dismissedAt: LocalDateTime?,
        val archivedAt: LocalDateTime?
    )

    fun create(projectId: UUID?, action: String, actor: String, message: String?, createdAt: LocalDateTime = LocalDateTime.now())
    fun list(recipient: String, severity: String?, action: String?, projectId: UUID?, from: LocalDateTime?, to: LocalDateTime?, includeArchived: Boolean, limit: Int, offset: Int = 0): List<Record>
    fun count(recipient: String, severity: String?, action: String?, projectId: UUID?, from: LocalDateTime?, to: LocalDateTime?, includeArchived: Boolean): Long
    fun mark(id: UUID, recipient: String, field: String): Boolean
}
