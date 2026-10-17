package com.gatekeeper.core.ports

fun interface PortProbe {
    fun isReachable(host: String, port: Int, timeoutMillis: Int): Boolean
}
