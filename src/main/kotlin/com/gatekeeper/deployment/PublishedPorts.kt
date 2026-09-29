package com.gatekeeper.deployment

/** Normalizes the published-port data recorded across deployment/runtime records. */
object PublishedPorts {
    fun normalize(ports: Map<Int, Int>, containerPort: Int?, hostPort: Int?): Map<Int, Int> {
        val validPorts = ports.filter { (container, host) -> container in 1..65535 && host in 1..65535 }
        if (validPorts.isNotEmpty()) return validPorts

        val validContainerPort = containerPort?.takeIf { it in 1..65535 } ?: return emptyMap()
        val validHostPort = hostPort?.takeIf { it in 1..65535 } ?: return emptyMap()
        return mapOf(validContainerPort to validHostPort)
    }

    fun applicationPort(containerPort: Int?, ports: Map<Int, Int>): Int? {
        val selectedContainerPort = containerPort?.takeIf { it in 1..65535 }
            ?: ports.keys.singleOrNull()?.takeIf { it in 1..65535 }
            ?: return null
        return ports[selectedContainerPort]?.takeIf { it in 1..65535 }
    }
}
