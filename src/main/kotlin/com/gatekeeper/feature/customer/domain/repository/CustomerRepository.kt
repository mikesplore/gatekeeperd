package com.gatekeeper.feature.customer.domain.repository

import java.time.LocalDateTime
import java.util.UUID

interface CustomerRepository {
    data class Record(val id: UUID, val firstName: String, val lastName: String, val contactEmail: String?, val contactPhone: String?, val billingStatus: String, val createdAt: LocalDateTime, val updatedAt: LocalDateTime) {
        val name: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
    }

    fun findAll(): List<Record>
    fun findPage(query: String?, limit: Int, offset: Int): Pair<List<Record>, Int>
    fun findById(id: UUID): Record?
    fun findByContactEmail(email: String): Record?
    fun existsByContactEmail(email: String): Boolean
    fun create(firstName: String, lastName: String, contactEmail: String?, contactPhone: String?, billingStatus: String = "unknown"): Record
    fun update(id: UUID, firstName: String, lastName: String, contactEmail: String?, contactPhone: String?): Record?
    fun delete(id: UUID): Boolean
}
