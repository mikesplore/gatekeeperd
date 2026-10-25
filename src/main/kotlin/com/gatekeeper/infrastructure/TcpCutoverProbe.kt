package com.gatekeeper.infrastructure

import com.gatekeeper.core.ports.CutoverProbe
import com.gatekeeper.feature.nginx.domain.service.NginxValidationService
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class TcpCutoverProbe(
    private val nginxValidator: NginxValidationService
) : CutoverProbe {
    override fun throughLocalNginx(domain: String, tls: Boolean): Boolean = runCatching {
        nginxValidator.requireValidHostname(domain)
        val port = if (tls) 443 else 80
        val raw = Socket()
        raw.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        raw.soTimeout = 3_000
        val socket = if (tls) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, domain, port, true).let { it as SSLSocket }.apply {
                    sslParameters = sslParameters.apply {
                        serverNames = listOf(SNIHostName(domain))
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    startHandshake()
                }
        } else raw
        socket.use {
            it.getOutputStream().write(
                "GET /.well-known/gatekeeperd-cutover-probe HTTP/1.1\r\nHost: $domain\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            val reader = it.getInputStream().bufferedReader(Charsets.US_ASCII)
            val statusLine = reader.readLine() ?: return@runCatching false
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return@runCatching false
            var marker = false
            while (true) {
                val header = reader.readLine() ?: return@runCatching false
                if (header.isEmpty()) break
                if (header.equals("X-Gatekeeperd-Cutover-Probe: stable", ignoreCase = true)) marker = true
            }
            status in 200..499 && marker
        }
    }.getOrDefault(false)

    override fun candidate(port: Int, domain: String, path: String): Boolean = runCatching {
        require(port in 1..65535)
        nginxValidator.requireValidHostname(domain)
        val safePath = path.takeIf { it.startsWith("/") && !it.contains('\r') && !it.contains('\n') } ?: "/"
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            socket.soTimeout = 3_000
            socket.getOutputStream().write(
                "GET $safePath HTTP/1.1\r\nHost: $domain\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)
            )
            val statusLine = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                ?: return@runCatching false
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return@runCatching false
            status in 200..499
        }
    }.getOrDefault(false)
}
