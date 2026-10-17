package com.gatekeeper.feature.integrations.data.persistence

import com.gatekeeper.db.repositories.GitHubCredentialRepository
import com.gatekeeper.db.tables.GitHubAppInstallations
import com.gatekeeper.db.tables.GitHubWebhookDeliveries
import com.gatekeeper.feature.integrations.domain.repository.GitHubCredentialStore
import com.gatekeeper.feature.integrations.domain.repository.GitHubInstallationStore
import com.gatekeeper.feature.integrations.domain.repository.GitHubWebhookStore
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class ExposedGitHubCredentialStore : GitHubCredentialStore {
    override fun privateKeyPem() = GitHubCredentialRepository.privateKeyPem()
    override fun webhookSecret() = GitHubCredentialRepository.webhookSecret()
    override fun save(type: String, value: String): GitHubCredentialStore.Version {
        val saved = GitHubCredentialRepository.save(type, value)
        return GitHubCredentialStore.Version(saved.id, saved.version)
    }
}

class ExposedGitHubInstallationStore : GitHubInstallationStore {
    override fun find(): GitHubInstallationStore.Record? = transaction {
        GitHubAppInstallations.selectAll().where { GitHubAppInstallations.id eq 1 }.singleOrNull()?.let {
            GitHubInstallationStore.Record(
                it[GitHubAppInstallations.installationId],
                it[GitHubAppInstallations.accountLogin],
                it[GitHubAppInstallations.accountType]
            )
        }
    }
    override fun save(installationId: Long, accountLogin: String?, accountType: String?) = transaction {
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
    override fun createPendingState(state: String) = transaction {
        val updated = GitHubAppInstallations.update({ GitHubAppInstallations.id eq 1 }) { it[pendingState] = state }
        if (updated == 0) GitHubAppInstallations.insert {
            it[id] = 1
            it[installationId] = 0
            it[pendingState] = state
        }
    }
    override fun consumePendingState(state: String): Boolean = transaction {
        val row = GitHubAppInstallations.selectAll().where { GitHubAppInstallations.id eq 1 }.singleOrNull()
        if (row?.get(GitHubAppInstallations.pendingState) != state) return@transaction false
        GitHubAppInstallations.update({ GitHubAppInstallations.id eq 1 }) { it[pendingState] = null }
        true
    }
    override fun clear() {
        transaction { GitHubAppInstallations.deleteWhere { GitHubAppInstallations.id eq 1 } }
    }
}

class ExposedGitHubWebhookStore : GitHubWebhookStore {
    override fun claim(deliveryId: String, event: String, repository: String?): Boolean = transaction {
        val before = GitHubWebhookDeliveries.selectAll()
            .where { GitHubWebhookDeliveries.deliveryId eq deliveryId }.count()
        GitHubWebhookDeliveries.insertIgnore {
            it[GitHubWebhookDeliveries.deliveryId] = deliveryId
            it[GitHubWebhookDeliveries.event] = event
            it[GitHubWebhookDeliveries.repository] = repository
        }
        before == 0L
    }
    override fun setQueuedCount(deliveryId: String, count: Int) {
        transaction {
            GitHubWebhookDeliveries.update({ GitHubWebhookDeliveries.deliveryId eq deliveryId }) {
                it[queuedCount] = count
            }
        }
    }
}
