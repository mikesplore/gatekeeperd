package com.gatekeeper.db.repositories

import com.gatekeeper.feature.notification.data.persistence.ExposedNotificationRepository
import java.time.LocalDateTime
import java.util.UUID

/** @deprecated Inject feature.notification.domain.repository.NotificationRepository instead. */
@Deprecated("Inject NotificationRepository")
object NotificationRepository {
    typealias NotificationRecord = com.gatekeeper.feature.notification.domain.repository.NotificationRepository.Record
    private val delegate = ExposedNotificationRepository()
    fun create(projectId: UUID?, action: String, actor: String, message: String?, createdAt: LocalDateTime = LocalDateTime.now()) = delegate.create(projectId, action, actor, message, createdAt)
    fun list(recipient: String, severity: String?, action: String?, projectId: UUID?, from: LocalDateTime?, to: LocalDateTime?, includeArchived: Boolean, limit: Int, offset: Int = 0) = delegate.list(recipient, severity, action, projectId, from, to, includeArchived, limit, offset)
    fun count(recipient: String, severity: String?, action: String?, projectId: UUID?, from: LocalDateTime?, to: LocalDateTime?, includeArchived: Boolean) = delegate.count(recipient, severity, action, projectId, from, to, includeArchived)
    fun mark(id: UUID, recipient: String, field: String) = delegate.mark(id, recipient, field)
}
