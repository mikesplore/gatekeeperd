package com.gatekeeper.db.repositories

import com.gatekeeper.feature.audit.data.persistence.ExposedAuditRepository
import com.gatekeeper.feature.notification.data.persistence.ExposedNotificationRepository
import org.jetbrains.exposed.sql.SortOrder
import java.util.UUID

/** @deprecated Inject feature.audit.domain.repository.AuditRepository instead. */
@Deprecated("Inject AuditRepository")
object AuditRepository {
    typealias AuditRecord = com.gatekeeper.feature.audit.domain.repository.AuditRepository.Record
    private val delegate = ExposedAuditRepository(ExposedNotificationRepository())
    fun write(projectId: UUID?, action: String, actor: String, reason: String?) = delegate.write(projectId, action, actor, reason)
    fun findByProjectId(projectId: UUID) = delegate.findByProjectId(projectId)
    fun findAll(limit: Int = 100) = delegate.findAll(limit)
    fun count(query: String? = null, action: String? = null) = delegate.count(query, action)
    fun findPage(limit: Int, offset: Int, query: String? = null, action: String? = null, sort: String = "createdAt", direction: SortOrder = SortOrder.DESC) =
        delegate.findPage(limit, offset, query, action, sort, if (direction == SortOrder.ASC) com.gatekeeper.feature.audit.domain.repository.AuditRepository.SortDirection.ASC else com.gatekeeper.feature.audit.domain.repository.AuditRepository.SortDirection.DESC)
    fun findFiltered(action: String?, actor: String?, limit: Int = 1000) = delegate.findFiltered(action, actor, limit)
}
