package com.gatekeeper.feature.customer.data.persistence

import com.gatekeeper.db.tables.SupportRequests
import com.gatekeeper.feature.customer.domain.repository.SupportRequestRepository
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ExposedSupportRequestRepository : SupportRequestRepository {
    override fun create(projectId: UUID, name: String?, email: String, message: String): UUID = transaction {
        SupportRequests.insert {
            it[SupportRequests.projectId] = projectId
            it[SupportRequests.requesterName] = name
            it[SupportRequests.requesterEmail] = email
            it[SupportRequests.message] = message
        }[SupportRequests.id]
    }
}