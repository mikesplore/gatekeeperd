package com.gatekeeper.deployment

import com.gatekeeper.core.ports.ProcessRunner

class DeploymentTriggerService(
    private val processRunner: ProcessRunner,
    private val systemctl: String = "/bin/systemctl",
) {
    fun trigger(): TriggerResult {
        val result = runCatching {
            processRunner.run(
                listOf("sudo", "-n", systemctl, "start", "--no-block", "gatekeeperd-auto-deploy.service")
            )
        }.getOrElse { error ->
            return TriggerResult(false, "Unable to start the deployment checker: ${error.message ?: error::class.simpleName}")
        }
        if (result.exitCode != 0) {
            val detail = (result.stderr.ifBlank { result.stdout }).trim().take(500)
            return TriggerResult(false, detail.ifBlank { "The deployment checker could not be started" })
        }
        return TriggerResult(true, null)
    }

    data class TriggerResult(val started: Boolean, val error: String?)
}
