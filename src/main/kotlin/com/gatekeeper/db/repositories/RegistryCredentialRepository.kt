package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.RegistryCredentials
import com.gatekeeper.security.SecretValueCipher
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDateTime

data class RegistryCredentialRecord(val registry: String, val username: String, val password: String, val updatedAt: LocalDateTime)

object RegistryCredentialRepository {
    fun list(): List<Pair<String, String>> = transaction { RegistryCredentials.selectAll().orderBy(RegistryCredentials.registry).map { it[RegistryCredentials.registry] to it[RegistryCredentials.username] } }
    fun find(registry: String): RegistryCredentialRecord? = transaction { RegistryCredentials.selectAll().where { RegistryCredentials.registry eq registry }.singleOrNull()?.let { RegistryCredentialRecord(it[RegistryCredentials.registry], it[RegistryCredentials.username], SecretValueCipher.decrypt(it[RegistryCredentials.passwordEncrypted]), it[RegistryCredentials.updatedAt]) } }
    fun save(registry: String, username: String, password: String) = transaction {
        val now = LocalDateTime.now()
        val encrypted = SecretValueCipher.encrypt(password)
        RegistryCredentials.upsert(RegistryCredentials.registry) {
            it[RegistryCredentials.registry] = registry; it[RegistryCredentials.username] = username; it[passwordEncrypted] = encrypted; it[updatedAt] = now
        }
    }
    fun delete(registry: String): Boolean = transaction { RegistryCredentials.deleteWhere { RegistryCredentials.registry eq registry } > 0 }
}
