package com.gatekeeper.deployment

import com.gatekeeper.core.ports.HttpPortProbe
import com.gatekeeper.core.ports.PortProbe
import com.gatekeeper.db.repositories.DeploymentJobRecord
import com.gatekeeper.docker.DockerGateway

data class ReadinessResult(val containerPort: Int?)

/** Checks a started deployment candidate without owning worker timing or retry policy. */
class ReadinessChecker(
    private val ports: PortProbe,
    private val http: HttpPortProbe
) {
    fun selectedProbe(job: DeploymentJobRecord, hasPublishedPort: Boolean): String =
        when (job.readinessType?.lowercase()) {
            "docker" -> "docker"
            "http" -> "http"
            "tcp" -> "tcp"
            "process" -> "process"
            else -> if (hasPublishedPort) "tcp (auto-detected)" else "process"
        }

    fun check(
        docker: DockerGateway,
        containerName: String,
        portMappings: Map<Int, Int>,
        job: DeploymentJobRecord
    ): ReadinessResult? {
        if (docker.containerHealth(containerName) != "running") return null
        val timeout = job.readinessProbeTimeoutMillis
        fun reachable(containerPort: Int): Boolean = portMappings[containerPort]
            ?.let { hostPort -> ports.isReachable("127.0.0.1", hostPort, timeout) } == true
        fun httpReachable(containerPort: Int, path: String): Boolean = portMappings[containerPort]
            ?.let { hostPort -> http.respondsSuccessfully("127.0.0.1", hostPort, path, timeout) } == true
        fun firstReachable(check: (Int) -> Boolean): Int? =
            (job.containerPort?.let(::listOf) ?: portMappings.keys.toList()).firstOrNull(check)

        val probe = selectedProbe(job, portMappings.isNotEmpty())
        return when (probe) {
            "docker" -> if (docker.dockerHealthStatus(containerName) == "healthy") {
                ReadinessResult(firstReachable(::reachable))
            } else null
            "http" -> {
                val path = job.readinessTarget?.takeIf { it.startsWith("/") } ?: "/"
                firstReachable { httpReachable(it, path) }?.let(::ReadinessResult)
            }
            "tcp", "tcp (auto-detected)" -> firstReachable(::reachable)?.let(::ReadinessResult)
            "process" -> ReadinessResult(job.containerPort ?: portMappings.keys.singleOrNull())
            else -> error("Unsupported readiness type: $probe")
        }
    }
}
