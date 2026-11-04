package com.gatekeeper.deployment

import com.gatekeeper.feature.deployment.domain.port.DeploymentCommandRunner
import com.gatekeeper.feature.deployment.domain.port.GitHubTokenProvider
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

class SourceFetcher(
    private val commands: DeploymentCommandRunner,
    private val githubTokens: GitHubTokenProvider
) {
    suspend fun fetch(
        repository: String,
        gitRef: String,
        workspace: Path,
        resetWorkspace: () -> Unit,
        checkCancelled: () -> Unit,
        onLog: (String) -> Unit,
        onOutput: (String, String) -> Unit
    ): String {
        val token = try {
            githubTokens.installationToken()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val reason = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
            onLog("GitHub App installation token could not be obtained: $reason. Attempting an unauthenticated clone for $repository.")
            ""
        }
        val clone = listOf("git", "clone", "--depth", "1", "--branch", gitRef, "https://github.com/$repository.git", workspace.toString())
        try {
            commands.run(
                command = clone,
                directory = workspace,
                environment = gitAuthEnvironment(token),
                checkCancelled = checkCancelled,
                onOutput = { onOutput(it, token) }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (token.isBlank()) {
                throw IllegalStateException("Git clone failed for $repository@$gitRef. See deployment diagnostics for Git output.", error)
            }
            val authenticatedFailure = (error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName)
                .replace(token, "[REDACTED]")
            onLog("Authenticated Git clone failed for $repository@$gitRef: $authenticatedFailure. Retrying without authentication.")
            resetWorkspace()
            try {
                commands.run(clone, workspace, checkCancelled = checkCancelled) { onOutput(it, "") }
            } catch (publicError: Exception) {
                if (publicError is CancellationException) throw publicError
                val publicFailure = publicError.message?.takeIf(String::isNotBlank) ?: publicError.javaClass.simpleName
                throw IllegalStateException(
                    "Git clone failed for $repository@$gitRef. Authenticated attempt: $authenticatedFailure. Unauthenticated attempt: $publicFailure. See deployment diagnostics for Git output.",
                    publicError
                )
            }
        }
        return commands.output(listOf("git", "rev-parse", "HEAD"), workspace).trim()
    }

    private fun gitAuthEnvironment(token: String): Map<String, String> = if (token.isBlank()) emptyMap() else mapOf(
        "GIT_CONFIG_COUNT" to "1",
        "GIT_CONFIG_KEY_0" to "http.extraheader",
        "GIT_CONFIG_VALUE_0" to "AUTHORIZATION: bearer $token"
    )
}
