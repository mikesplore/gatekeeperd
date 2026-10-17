package com.gatekeeper.nginx

import com.gatekeeper.core.ports.ProcessRunner
import com.gatekeeper.infrastructure.ProcessRunnerImpl
import java.time.OffsetDateTime

/** Executes the host nginx commands while keeping command handling out of the service orchestration. */
class NginxCommandRunner(private val processRunner: ProcessRunner = ProcessRunnerImpl()) {
    fun test(): NginxTestResult = try {
        val result = processRunner.run(listOf("sudo", "-n", "/usr/sbin/nginx", "-t"))
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
        processRunner.run(listOf("sudo", "-n", "/bin/systemctl", "reload", "nginx")).exitCode == 0
    } catch (_: Exception) {
        false
    }

    fun dumpConfig(): String? = runCatching {
        val result = processRunner.run(listOf("sudo", "-n", "/usr/sbin/nginx", "-T"))
        if (result.exitCode == 0) listOf(result.stdout, result.stderr).filter(String::isNotBlank).joinToString("\n") else null
    }.getOrNull()
}
