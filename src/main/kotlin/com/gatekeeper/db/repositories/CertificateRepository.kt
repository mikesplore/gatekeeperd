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
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
    fun syncInventory(domain: String, expiresAt: LocalDateTime?, renewalStatus: String): CertificateRecord = transaction {
        val existing = Certificates.selectAll().where { Certificates.domain eq domain }.singleOrNull()
        if (existing == null) {
            Certificates.insert {
                it[Certificates.domain] = domain
                it[Certificates.expiresAt] = expiresAt
                it[Certificates.renewalStatus] = renewalStatus
            }
        } else {
            Certificates.update({ Certificates.domain eq domain }) {
                it[Certificates.expiresAt] = expiresAt
                it[Certificates.renewalStatus] = renewalStatus
            }
        }
        findByDomain(domain)!!
    }
    fun activeSiteCount(domain: String): Long = transaction {
        (Sites innerJoin Certificates innerJoin Projects).selectAll().where { (Certificates.domain eq domain) and Projects.deletedAt.isNull() }.count()
    }
    fun requireRemovable(domain: String, linkedActiveSites: Long = activeSiteCount(domain)) {
        require(linkedActiveSites == 0L) { "Certificate '$domain' is still referenced by active sites" }
    }
    fun deleteByDomain(domain: String) = transaction {
        Certificates.deleteWhere { Certificates.domain eq domain }
    }
    private fun org.jetbrains.exposed.sql.ResultRow.toRecord() = CertificateRecord(this[Certificates.id], this[Certificates.domain], this[Certificates.issuedAt], this[Certificates.expiresAt], this[Certificates.renewalStatus], this[Certificates.lastRenewalAttempt], this[Certificates.lastRenewalError])
}
