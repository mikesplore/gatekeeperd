package com.gatekeeper.db.repositories

import com.gatekeeper.db.tables.GitHubAppInstallations
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class GitHubInstallationRecord(val installationId: Long, val accountLogin: String?, val accountType: String?)

object GitHubAppInstallationRepository {
    fun find(): GitHubInstallationRecord? = transaction {
        GitHubAppInstallations.selectAll().where { GitHubAppInstallations.id eq 1 }.singleOrNull()?.let {
            GitHubInstallationRecord(it[GitHubAppInstallations.installationId], it[GitHubAppInstallations.accountLogin], it[GitHubAppInstallations.accountType])
        }
    }

    fun save(installationId: Long, accountLogin: String?, accountType: String?) = transaction {
        val updated = GitHubAppInstallations.update({ GitHubAppInstallations.id eq 1 }) {
            it[GitHubAppInstallations.installationId] = installationId
            it[GitHubAppInstallations.accountLogin] = accountLogin
            it[GitHubAppInstallations.accountType] = accountType
        }
        if (updated == 0) GitHubAppInstallations.insert {
            it[id] = 1
            it[GitHubAppInstallations.installationId] = installationId
            it[GitHubAppInstallations.accountLogin] = accountLogin
            it[GitHubAppInstallations.accountType] = accountType
        }
    }

    fun createPendingState(state: String) = transaction {
        val updated = GitHubAppInstallations.update({ GitHubAppInstallations.id eq 1 }) { it[pendingState] = state }
        if (updated == 0) GitHubAppInstallations.insert { it[id] = 1; it[installationId] = 0; it[pendingState] = state }
    }

    fun consumePendingState(state: String): Boolean = transaction {
        val row = GitHubAppInstallations.selectAll().where { GitHubAppInstallations.id eq 1 }.singleOrNull()
        if (row?.get(GitHubAppInstallations.pendingState) != state) return@transaction false
        GitHubAppInstallations.update({ GitHubAppInstallations.id eq 1 }) { it[pendingState] = null }
        true
    }
}
