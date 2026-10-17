package com.gatekeeper.feature.certificate.data.persistence

import com.gatekeeper.db.tables.Certificates
import com.gatekeeper.db.tables.Projects
import com.gatekeeper.db.tables.Sites
import com.gatekeeper.feature.certificate.domain.repository.CertificateRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDateTime
import java.util.UUID

class ExposedCertificateRepository : CertificateRepository {
    override fun upsert(domain: String, issuedAt: LocalDateTime?, expiresAt: LocalDateTime?, renewalStatus: String, error: String?) = transaction {
        val existing = Certificates.selectAll().where { Certificates.domain eq domain }.singleOrNull()
        if (existing == null) Certificates.insert {
            it[Certificates.domain] = domain; it[Certificates.issuedAt] = issuedAt; it[Certificates.expiresAt] = expiresAt
            it[Certificates.renewalStatus] = renewalStatus; it[Certificates.lastRenewalAttempt] = LocalDateTime.now(); it[Certificates.lastRenewalError] = error
        } else Certificates.update({ Certificates.domain eq domain }) {
            it[Certificates.issuedAt] = issuedAt; it[Certificates.expiresAt] = expiresAt; it[Certificates.renewalStatus] = renewalStatus
            it[Certificates.lastRenewalAttempt] = LocalDateTime.now(); it[Certificates.lastRenewalError] = error
        }
        findByDomainInTransaction(domain)!!
    }

    override fun findByDomain(domain: String) = transaction { findByDomainInTransaction(domain) }
    override fun findAll() = transaction { Certificates.selectAll().orderBy(Certificates.expiresAt, SortOrder.ASC).map { it.toRecord() } }

    override fun syncInventory(domain: String, expiresAt: LocalDateTime?, renewalStatus: String) = updateInventory(domain, expiresAt, renewalStatus, false, null)
    override fun recordRenewalResult(domain: String, expiresAt: LocalDateTime?, renewalStatus: String, error: String?) = updateInventory(domain, expiresAt, renewalStatus, true, error)

    private fun updateInventory(domain: String, expiresAt: LocalDateTime?, renewalStatus: String, renewalAttempt: Boolean, error: String?) = transaction {
        val existing = Certificates.selectAll().where { Certificates.domain eq domain }.singleOrNull()
        if (existing == null) Certificates.insert {
            it[Certificates.domain] = domain; it[Certificates.expiresAt] = expiresAt; it[Certificates.renewalStatus] = renewalStatus
            if (renewalAttempt) { it[Certificates.lastRenewalAttempt] = LocalDateTime.now(); it[Certificates.lastRenewalError] = error }
        } else Certificates.update({ Certificates.domain eq domain }) {
            it[Certificates.expiresAt] = expiresAt; it[Certificates.renewalStatus] = renewalStatus
            if (renewalAttempt) { it[Certificates.lastRenewalAttempt] = LocalDateTime.now(); it[Certificates.lastRenewalError] = error }
        }
        findByDomainInTransaction(domain)!!
    }

    override fun activeSiteCount(domain: String) = transaction {
        (Sites innerJoin Certificates innerJoin Projects).selectAll().where { (Certificates.domain eq domain) and Projects.deletedAt.isNull() }.count()
    }
    override fun requireRemovable(domain: String, linkedActiveSites: Long) { require(linkedActiveSites == 0L) { "Certificate '$domain' is still referenced by active sites" } }
    override fun deleteByDomain(domain: String) { transaction { Certificates.deleteWhere { Certificates.domain eq domain } } }

    private fun findByDomainInTransaction(domain: String) = Certificates.selectAll().where { Certificates.domain eq domain }.singleOrNull()?.toRecord()
    private fun ResultRow.toRecord() = CertificateRepository.Record(this[Certificates.id], this[Certificates.domain], this[Certificates.issuedAt], this[Certificates.expiresAt], this[Certificates.renewalStatus], this[Certificates.lastRenewalAttempt], this[Certificates.lastRenewalError])
}
