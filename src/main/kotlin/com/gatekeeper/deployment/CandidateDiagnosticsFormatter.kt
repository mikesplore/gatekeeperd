package com.gatekeeper.deployment

import com.gatekeeper.docker.DockerContainerDiagnostics

internal object CandidateDiagnosticsFormatter {
    fun format(diagnostics: DockerContainerDiagnostics?): String = buildString {
        appendLine("--- candidate Docker runtime state ---")
        if (diagnostics == null) {
            appendLine("Docker inspect details were unavailable before candidate cleanup")
            return@buildString
        }

        appendLine("state=${diagnostics.state}")
        appendLine("restartCount=${diagnostics.restartCount}")
        appendLine("exitCode=${diagnostics.exitCode ?: "unavailable"}")
        appendLine("oomKilled=${diagnostics.oomKilled}")
        diagnostics.error?.let { appendLine("dockerError=$it") }
        diagnostics.startedAt?.let { appendLine("startedAt=$it") }
        diagnostics.finishedAt?.let { appendLine("finishedAt=$it") }
        diagnostics.healthStatus?.let { appendLine("healthStatus=$it") }
        diagnostics.healthLog.forEachIndexed { index, check ->
            appendLine("healthCheck[$index]: exitCode=${check.exitCode ?: "unavailable"} startedAt=${check.startedAt ?: "unavailable"} endedAt=${check.endedAt ?: "unavailable"}")
            check.output?.let { appendLine(it) }
        }
    }
}
