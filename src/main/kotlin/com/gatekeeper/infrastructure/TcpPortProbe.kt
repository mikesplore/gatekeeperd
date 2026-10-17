package com.gatekeeper.infrastructure

import com.gatekeeper.core.ports.PortProbe
import java.net.InetSocketAddress
import java.net.Socket

class TcpPortProbe : PortProbe {
    override fun isReachable(host: String, port: Int, timeoutMillis: Int): Boolean {
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(timeoutMillis >= 0) { "Timeout must not be negative" }
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
            }
            true
        }.getOrDefault(false)
    }
}
