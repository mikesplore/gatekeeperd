package com.gatekeeper.core.ports

interface PortProbe {
    fun isReachable(host: String, port: Int, timeoutMillis: Int): Boolean
}
