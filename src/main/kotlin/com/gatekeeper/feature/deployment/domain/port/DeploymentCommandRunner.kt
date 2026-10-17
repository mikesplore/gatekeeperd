package com.gatekeeper.feature.deployment.domain.port

import java.nio.file.Path

interface DeploymentCommandRunner {
    fun run(
        command: List<String>,
        directory: Path,
        environment: Map<String, String> = emptyMap(),
        stdin: String? = null,
        checkCancelled: () -> Unit,
        onOutput: (String) -> Unit = {}
    )

    fun output(command: List<String>, directory: Path): String
}
