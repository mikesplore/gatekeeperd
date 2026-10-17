package com.gatekeeper.core.ports

fun interface HttpPortProbe {
    fun respondsSuccessfully(host: String, port: Int, path: String, timeoutMillis: Int): Boolean
}
