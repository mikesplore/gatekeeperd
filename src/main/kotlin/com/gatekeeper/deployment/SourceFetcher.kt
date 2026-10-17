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
            onLog("GitHub App is not connected; attempting public repository clone for $repository")
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
            if (token.isBlank()) throw error
            onLog("Authenticated clone unavailable for $repository; retrying as public repository")
            resetWorkspace()
            commands.run(clone, workspace, checkCancelled = checkCancelled) { onOutput(it, "") }
        }
        return commands.output(listOf("git", "rev-parse", "HEAD"), workspace).trim()
    }

    private fun gitAuthEnvironment(token: String): Map<String, String> = if (token.isBlank()) emptyMap() else mapOf(
        "GIT_CONFIG_COUNT" to "1",
        "GIT_CONFIG_KEY_0" to "http.extraheader",
        "GIT_CONFIG_VALUE_0" to "AUTHORIZATION: bearer $token"
    )
}
