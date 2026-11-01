package com.gatekeeper.auth

import com.gatekeeper.db.tables.PasswordResetTokens
import com.gatekeeper.db.tables.Users
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.util.UUID

class ExposedAuthUserRepository : AuthUserRepository {
    override fun findByEmail(email: String): AuthUser? = transaction {
        Users.selectAll().where { Users.email eq email }.singleOrNull()?.toAuthUser()
    }

    override fun findById(id: UUID): AuthUser? = transaction {
        Users.selectAll().where { Users.id eq id }.singleOrNull()?.toAuthUser()
    }

    override fun createPasswordResetToken(userId: UUID, tokenHash: String, expiresAt: LocalDateTime) = transaction {
        PasswordResetTokens.deleteWhere { PasswordResetTokens.userId eq userId }
        PasswordResetTokens.insert {
            it[PasswordResetTokens.userId] = userId
            it[PasswordResetTokens.tokenHash] = tokenHash
            it[PasswordResetTokens.expiresAt] = expiresAt
        }
        Unit
    }

    override fun findPasswordResetToken(tokenHash: String): PasswordResetToken? = transaction {
        PasswordResetTokens.selectAll().where {
            (PasswordResetTokens.tokenHash eq tokenHash) and PasswordResetTokens.usedAt.isNull()
        }.singleOrNull()?.let {
            PasswordResetToken(it[PasswordResetTokens.id], it[PasswordResetTokens.userId], it[PasswordResetTokens.expiresAt], it[PasswordResetTokens.usedAt])
        }
    }

    override fun completePasswordReset(tokenId: UUID, userId: UUID, hash: String, usedAt: LocalDateTime) = transaction {
        Users.update({ Users.id eq userId }) { it[Users.passwordHash] = hash }
        PasswordResetTokens.update({ PasswordResetTokens.id eq tokenId }) { it[PasswordResetTokens.usedAt] = usedAt }
        Unit
    }

    override fun replaceRecoveryCodes(userId: UUID, codes: List<String>) = transaction {
        Users.update({ Users.id eq userId }) { it[Users.recoveryCodes] = codes }
        Unit
    }

    override fun updateTwoFactor(userId: UUID, secret: String?, enabled: Boolean, recoveryCodes: List<String>) = transaction {
        Users.update({ Users.id eq userId }) {
            it[Users.totpSecret] = secret
            it[Users.totpEnabled] = enabled
            it[Users.recoveryCodes] = recoveryCodes
        }
        Unit
    }

    override fun updateDisplayName(email: String, displayName: String) = transaction {
        Users.update({ Users.email eq email }) { it[Users.displayName] = displayName.takeIf(String::isNotBlank) }
        Unit
    }

    override fun updatePassword(email: String, hash: String) = transaction {
        Users.update({ Users.email eq email }) { it[Users.passwordHash] = hash }
        Unit
    }

    override fun listUsers(query: String?, limit: Int, offset: Long): Pair<List<AuthUser>, Long> = transaction {
        val statement = Users.selectAll()
        query?.takeIf(String::isNotBlank)?.let { value -> statement.andWhere { Users.email like "%$value%" } }
        val total = statement.count()
        val users = statement.orderBy(Users.createdAt).limit(limit, offset).map { it.toAuthUser() }
        users to total
    }

    override fun createAdmin(email: String, passwordHash: String, role: String): AuthUser = transaction {
        if (Users.selectAll().where { Users.email.lowerCase() eq email }.singleOrNull() != null) {
            throw IllegalArgumentException("duplicate")
        }
        val id = UUID.randomUUID()
        Users.insert {
            it[Users.id] = id
            it[Users.email] = email
            it[Users.passwordHash] = passwordHash
            it[Users.role] = role
            it[Users.totpEnabled] = false
            it[Users.recoveryCodes] = emptyList()
        }
        Users.selectAll().where { Users.id eq id }.single().toAuthUser()
    }

    override fun updateAdmin(id: UUID, email: String?, role: String?, active: Boolean?): AuthUser? = transaction {
        Users.update({ Users.id eq id }) { statement ->
            email?.let { statement[Users.email] = it }
            role?.let { statement[Users.role] = it }
            active?.let { statement[Users.active] = it }
        }
        Users.selectAll().where { Users.id eq id }.singleOrNull()?.toAuthUser()
    }

    override fun deleteAdmin(id: UUID): Boolean = transaction { Users.deleteWhere { Users.id eq id } > 0 }

    override fun countAdmins(): Long = transaction { Users.selectAll().count { it[Users.role] == "admin" }.toLong() }

    private fun ResultRow.toAuthUser() = AuthUser(
        id = this[Users.id], email = this[Users.email], displayName = this[Users.displayName],
        avatarUrl = this[Users.avatarUrl], totpSecret = this[Users.totpSecret], totpEnabled = this[Users.totpEnabled],
        recoveryCodes = this[Users.recoveryCodes].toList(), passwordHash = this[Users.passwordHash],
        role = this[Users.role], active = this[Users.active], createdAt = this[Users.createdAt]
    )
}
