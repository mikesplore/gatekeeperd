package com.gatekeeper.infrastructure

import com.gatekeeper.core.ports.HttpPortProbe
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class TcpHttpPortProbe : HttpPortProbe {
    override fun respondsSuccessfully(host: String, port: Int, path: String, timeoutMillis: Int): Boolean {
        require(port in 1..65535) { "Port must be between 1 and 65535" }
        require(path.startsWith("/")) { "HTTP readiness path must be absolute" }
        require(timeoutMillis >= 0) { "Timeout must not be negative" }
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                socket.soTimeout = timeoutMillis
                socket.getOutputStream().write(
                    "GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
                )
                val status = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                    .readLine()?.split(' ')?.getOrNull(1)?.toIntOrNull()
                status != null && status in 200..499
            }
        }.getOrDefault(false)
    }
}
