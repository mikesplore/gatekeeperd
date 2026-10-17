package com.gatekeeper.feature.integrations.domain.repository

interface GitHubInstallationStore {
    data class Record(val installationId: Long, val accountLogin: String?, val accountType: String?)
    fun find(): Record?
    fun save(installationId: Long, accountLogin: String?, accountType: String?)
    fun createPendingState(state: String)
    fun consumePendingState(state: String): Boolean
    fun clear()
}
