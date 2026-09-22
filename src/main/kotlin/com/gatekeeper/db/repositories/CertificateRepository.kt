package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.Certificates
import com.gatekeeper.db.tables.Projects
import com.gatekeeper.db.tables.Sites
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.util.UUID

object CertificateRepository {
    data class CertificateRecord(val id: UUID, val domain: String, val issuedAt: LocalDateTime?, val expiresAt: LocalDateTime?, val renewalStatus: String, val lastRenewalAttempt: LocalDateTime?, val lastRenewalError: String?)

    fun upsert(domain: String, issuedAt: LocalDateTime?, expiresAt: LocalDateTime?, renewalStatus: String, error: String? = null): CertificateRecord = transaction {
        val existing = Certificates.selectAll().where { Certificates.domain eq domain }.singleOrNull()
        if (existing == null) {
            Certificates.insert { it[Certificates.domain] = domain; it[Certificates.issuedAt] = issuedAt; it[Certificates.expiresAt] = expiresAt; it[Certificates.renewalStatus] = renewalStatus; it[Certificates.lastRenewalAttempt] = LocalDateTime.now(); it[Certificates.lastRenewalError] = error }
        } else {
            Certificates.update({ Certificates.domain eq domain }) { it[Certificates.issuedAt] = issuedAt; it[Certificates.expiresAt] = expiresAt; it[Certificates.renewalStatus] = renewalStatus; it[Certificates.lastRenewalAttempt] = LocalDateTime.now(); it[Certificates.lastRenewalError] = error }
        }
        findByDomain(domain)!!
    }

    fun findByDomain(domain: String): CertificateRecord? = transaction { Certificates.selectAll().where { Certificates.domain eq domain }.singleOrNull()?.toRecord() }
    fun findAll(): List<CertificateRecord> = transaction { Certificates.selectAll().orderBy(Certificates.expiresAt, SortOrder.ASC).map { it.toRecord() } }
    fun activeSiteCount(domain: String): Long = transaction {
        (Sites innerJoin Certificates innerJoin Projects).selectAll().where { (Certificates.domain eq domain) and Projects.deletedAt.isNull() }.count()
    }
    private fun org.jetbrains.exposed.sql.ResultRow.toRecord() = CertificateRecord(this[Certificates.id], this[Certificates.domain], this[Certificates.issuedAt], this[Certificates.expiresAt], this[Certificates.renewalStatus], this[Certificates.lastRenewalAttempt], this[Certificates.lastRenewalError])
}
