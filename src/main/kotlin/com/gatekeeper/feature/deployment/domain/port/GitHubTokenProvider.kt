package com.gatekeeper.feature.deployment.domain.port

fun interface GitHubTokenProvider {
    suspend fun installationToken(): String
}
