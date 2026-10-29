package com.gatekeeper.feature.nginx.domain.port

import com.gatekeeper.core.ports.ProcessRunner
import com.gatekeeper.feature.nginx.presentation.NginxTestResult
import com.gatekeeper.feature.nginx.domain.port.NginxEnvironmentPolicy
import com.gatekeeper.infrastructure.ProcessRunnerImpl
import java.time.OffsetDateTime

/**
 * Executes the host nginx commands while keeping command handling out of the
 * service orchestration. The privilege policy is supplied by the environment,
 * so local development can run against a user-owned nginx without sudo.
 */
class NginxCommandRunner(
    internal val processRunner: ProcessRunner = ProcessRunnerImpl(),
    internal val policy: NginxEnvironmentPolicy
) {

    fun test(): NginxTestResult = try {
        val result = processRunner.run(policy.nginxTestCommand())
        val output = listOf(result.stdout, result.stderr).filter(String::isNotBlank).joinToString("\n")
        NginxTestResult(result.exitCode == 0, result.exitCode, output, OffsetDateTime.now().toString())
    } catch (e: Exception) {
        NginxTestResult(
            false,
            -1,
            "Unable to execute nginx -t non-interactively: ${e.message ?: "unknown error"}. Configure sudoers NOPASSWD for the Gatekeeperd service account.",
            OffsetDateTime.now().toString()
        )
    }

    fun reload(): Boolean = try {
        processRunner.run(policy.nginxReloadCommand()).exitCode == 0
    } catch (_: Exception) {
        false
    }

    fun dumpConfig(): String? = runCatching {
        val result = processRunner.run(policy.nginxDumpCommand())
        if (result.exitCode == 0) listOf(result.stdout, result.stderr).filter(String::isNotBlank).joinToString("\n") else null
    }.getOrNull()
}
