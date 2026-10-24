package com.gatekeeper.feature.nginx.domain.port

import com.gatekeeper.core.ports.PortProbe

class PortProbe(
    private val probe: PortProbe
) {
    fun isPortActive(port: Int): Boolean {
        return try {
            listOf("127.0.0.1", "::1").any { host ->
                probe.isReachable(host, port, 250)
            }
        } catch (_: Exception) {
            false
        }
    }
}