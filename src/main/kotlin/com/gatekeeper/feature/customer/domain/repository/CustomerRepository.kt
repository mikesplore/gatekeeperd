package com.gatekeeper.feature.customer.domain.repository

import java.time.LocalDateTime
import java.util.UUID

interface CustomerRepository {
    data class Record(val id: UUID, val name: String, val contactEmail: String?, val contactPhone: String?, val billingStatus: String, val createdAt: LocalDateTime, val updatedAt: LocalDateTime)

    fun findAll(): List<Record>
    fun findPage(query: String?, limit: Int, offset: Int): Pair<List<Record>, Int>
    fun findById(id: UUID): Record?
    fun existsByContactEmail(email: String): Boolean
    fun create(name: String, contactEmail: String?, contactPhone: String?, billingStatus: String = "unknown"): Record
}
