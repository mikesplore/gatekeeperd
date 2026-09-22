package com.gatekeeper.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

object Certificates : Table("certificates") {
    val id = uuid("id").autoGenerate()
    val domain = text("domain").uniqueIndex()
    val issuedAt = datetime("issued_at").nullable()
    val expiresAt = datetime("expires_at").nullable()
    val renewalStatus = text("renewal_status").default("unknown")
    val lastRenewalAttempt = datetime("last_renewal_attempt").nullable()
    val lastRenewalError = text("last_renewal_error").nullable()
    override val primaryKey = PrimaryKey(id)
}
